package com.swxctx.aiclient;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.JsonReader;
import android.util.Log;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kotlin.Pair;

/**
 * @Author swxctx
 * @Date 2024-04-10
 * @Describe: 模型记载及调用
 */
public class TFLiteModel {
    private static String TAG = "TFLiteModel";
    private static final String MODEL_PATH = "64-fp16.tflite";
    private static String VOCAB_PATH = "vocab.json";
    private static String MERGES_PATH = "merges.txt";
    // 模型期望的序列长度
    private static final int SEQUENCE_LENGTH = 64;
    // 词汇表大小
    private static final int VOCAB_SIZE = 50257;
    // int32类型每个元素4字节
    private static final int bytesPerInputElement = 4;
    // 模型推理解释器
    private Interpreter interpreter;
    // 模型分词器
    private GPT2Tokenizer tokenizer;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public TFLiteModel(Context context) {
        try {
            // 处理分词器
            Future<Map<String, Integer>> encoderFuture = loadEncoder(context, VOCAB_PATH);
            Map<String, Integer> encoder = encoderFuture.get();

            Map<Integer, String> decoder = new HashMap<>();
            for (Map.Entry<String, Integer> entry : encoder.entrySet()) {
                decoder.put(entry.getValue(), entry.getKey());
            }

            // bpe算法处理
            Future<Map<Pair<String, String>, Integer>> bpeRanksFuture = loadBpeRanks(context, MERGES_PATH);
            Map<Pair<String, String>, Integer> bpeRanks = bpeRanksFuture.get();

            // 分词器初始化
            tokenizer = new GPT2Tokenizer(encoder, decoder, bpeRanks);

            // 模型调用初始化
            this.interpreter = new Interpreter(loadModelFile(context, MODEL_PATH));

            // 获取模型输入和输出张量的信息
            int inputSize = interpreter.getInputTensor(0).numElements();
            int outputSize = interpreter.getOutputTensor(0).numElements();
            Log.d(TAG, "Input size: " + inputSize);
            Log.d(TAG, "Output size: " + outputSize);

            Toast.makeText(context, "初始化成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error loading model", e);
        }
    }

    /**
     * 模型加载
     *
     * @param context
     * @param modelPath
     * @return
     * @throws IOException
     */
    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        // 从assets目录获取模型文件的AssetFileDescriptor
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);

        // 使用AssetFileDescriptor创建一个FileInputStream
        // 这个FileInputStream指向我们要加载的模型文件
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());

        // 从FileInputStream获取FileChannel
        // FileChannel提供了一个连接到文件的通道，允许我们高效地读写文件
        FileChannel fileChannel = inputStream.getChannel();

        // 获取模型文件在assets文件中的开始偏移量
        // 这是因为assets中的文件可能会被打包到一个更大的容器文件中，所以需要知道实际数据的开始位置
        long startOffset = fileDescriptor.getStartOffset();

        // 获取声明的模型文件长度
        // 这是模型文件的实际大小
        long declaredLength = fileDescriptor.getDeclaredLength();

        // 使用FileChannel将模型文件映射到内存中
        // MapMode.READ_ONLY表示这个内存区域是只读的
        // startOffset和declaredLength指定了要映射的文件区域
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * 文字编码
     *
     * @param context
     * @param VOCAB_PATH
     * @return
     */
    public Future<Map<String, Integer>> loadEncoder(Context context, final String VOCAB_PATH) {
        // 使用ExecutorService提交一个Callable任务，这允许我们在另一个线程中异步执行耗时操作
        // 避免阻塞UI线程
        return executor.submit(new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() throws Exception {
                // 创建一个HashMap来存储词汇和其对应的整数ID
                HashMap<String, Integer> map = new HashMap<>();
                // 使用JsonReader读取VOCAB_PATH指向的JSON文件
                // 这个文件包含了需要加载的词汇编码器数据
                try (JsonReader reader = new JsonReader(new InputStreamReader(context.getAssets().open(VOCAB_PATH), "UTF-8"))) {
                    // 开始解析JSON对象
                    reader.beginObject();
                    // 循环遍历JSON对象中的所有键值对
                    while (reader.hasNext()) {
                        // 键（key）表示词汇（token）
                        String key = reader.nextName();
                        // 值（value）表示词汇对应的整数ID
                        int value = reader.nextInt();
                        // 将键值对存储到map中
                        map.put(key, value);
                    }
                    // 结束解析JSON对象
                    reader.endObject();
                }
                // 返回包含了所有词汇编码的HashMap
                return map;
            }
        });
    }

    /**
     * 文字解码
     *
     * @param context
     * @param MERGES_PATH
     * @return
     */
    public Future<Map<Pair<String, String>, Integer>> loadBpeRanks(Context context, final String MERGES_PATH) {
        // 使用ExecutorService提交一个Callable任务，这允许我们在另一个线程中异步执行耗时操作
        return executor.submit(new Callable<Map<Pair<String, String>, Integer>>() {
            @Override
            public Map<Pair<String, String>, Integer> call() throws Exception {
                // 创建一个HashMap来存储BPE的合并规则，规则被存储为字符串对和它们的合并优先级
                HashMap<Pair<String, String>, Integer> map = new HashMap<>();
                // 使用BufferedReader来读取MERGES_PATH指向的文件
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(MERGES_PATH)))) {
                    String line;
                    int index = 0; // 用于跟踪当前读取的行号
                    while ((line = reader.readLine()) != null) {
                        // 第一行通常是头部信息，所以跳过
                        if (index++ == 0) continue;
                        // 分割每一行为两部分，这两部分代表需要合并的字符串对
                        String[] parts = line.split(" ");
                        // 如果分割后不是两部分，跳过这一行
                        if (parts.length < 2) continue;
                        // 创建一个字符串对作为合并规则的键
                        Pair<String, String> pair = new Pair<>(parts[0], parts[1]);
                        // 将这个字符串对和它的索引（减去2是因为跳过了头部，且索引从0开始）放入映射中
                        map.put(pair, index - 2);
                    }
                }
                // 返回构建好的BPE合并规则映射
                return map;
            }
        });
    }

    /**
     * 文本生成
     *
     * @param inputText
     * @param nbTokens  生成数量控制
     * @return
     */
    public String generateText(String inputText, int nbTokens) {
        Log.d(TAG, "generate: inputText-> " + inputText + ", nbTokens-> " + nbTokens);

        // 编码输入文本
        List<Integer> inputIdsList = tokenizer.encode(inputText);
        StringBuilder sb = new StringBuilder();

        // 主循环，控制生成的tokens数量
        for (int n = 0; n < nbTokens; n++) {
            // 如果超出最大长度，移除最前面的元素以保持inputIdsList的长度
            if (inputIdsList.size() > SEQUENCE_LENGTH) {
                inputIdsList = inputIdsList.subList(inputIdsList.size() - SEQUENCE_LENGTH, inputIdsList.size());
            }

            int[] inputIds = inputIdsList.stream().mapToInt(i -> i).toArray();
            Log.d(TAG, "generate: inputIds-> " + Arrays.toString(inputIds));

            // 根据模型输入形状，准备输入数据
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(SEQUENCE_LENGTH * bytesPerInputElement).order(ByteOrder.nativeOrder());
            for (int i = 0; i < SEQUENCE_LENGTH; i++) {
                inputBuffer.putInt(i < inputIds.length ? inputIds[i] : 0); // 使用0作为填充值
            }
            inputBuffer.rewind();

            // 准备模型输出数据结构
            float[][][] output = new float[1][SEQUENCE_LENGTH][VOCAB_SIZE];

            // 执行模型推理
            interpreter.run(inputBuffer, output);

            // 基于实际的inputIds长度确定输出中对应的概率分布的位置
            // 因为之前填充了一些无效值，所以输出同样需要到指定位置处理
            int outputIndex = Math.min(inputIds.length, SEQUENCE_LENGTH) - 1;
            float[] probabilities = output[0][outputIndex];
            // 在推理之后，检查并打印最后一个时间步的概率分布
            Log.d(TAG, "probabilities: " + Arrays.toString(probabilities));
            int maxIndex = argMax(probabilities);

            // 移除之前的补位填充
            for (int i = inputIdsList.size() - 1; i >= 0; i--) {
                if (inputIdsList.get(i) == 0) {
                    inputIdsList.remove(i);
                } else {
                    // 一旦遇到非0元素，停止移除操作
                    break;
                }
            }

            // 添加到input中，用于下次生成
            inputIdsList.add(maxIndex);

            // 解码最高概率的词汇ID，并添加到最终文本中
            String decodedToken = tokenizer.decode(Arrays.asList(maxIndex));
            // 添加到结果输出中
            sb.append(decodedToken).append(" ");
        }

        // 返回生成的文本
        String outputString = sb.toString().trim();
        Log.d(TAG, "Generated Text: " + outputString);

        // 集合输入一起返回
        return inputText + outputString;
    }

    /**
     * 找到概率最高的索引
     *
     * @param probabilities
     * @return
     */
    private int argMax(float[] probabilities) {
        int maxIndex = 0;
        float maxProbability = probabilities[0];
        for (int j = 1; j < probabilities.length; j++) {
            if (probabilities[j] > maxProbability) {
                maxProbability = probabilities[j];
                maxIndex = j;
            }
        }
        return maxIndex;
    }
}