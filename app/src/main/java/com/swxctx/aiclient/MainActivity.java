package com.swxctx.aiclient;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.swxctx.aiclient.R;

public class MainActivity extends AppCompatActivity {
    private EditText etInput;
    // 控制生成的token数量
    private EditText etToken;
    private TextView tvGen;
    private TextView tvClear;
    private TextView tvResult;

    // GPT2模型加载
    private TFLiteModel aiModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        bindListen();

        // 模型加载及初始化
        aiModel = new TFLiteModel(this);
    }

    private void initView() {
        etInput = (EditText) findViewById(R.id.et_input);
        etToken = (EditText) findViewById(R.id.et_token);
        tvGen = (TextView) findViewById(R.id.tv_gen);
        tvClear = (TextView) findViewById(R.id.tv_clear);
        tvResult = (TextView) findViewById(R.id.tv_result);
    }

    private void bindListen(){
        tvGen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvGen.setText("生成中...");
                tvGen.setClickable(false);
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        int nbToken = Integer.parseInt(etToken.getText().toString());
                        String result = aiModel.generateText(etInput.getText().toString(), nbToken);
                        Toast.makeText(MainActivity.this, "生成成功", Toast.LENGTH_SHORT).show();
                        tvResult.setText(result);
                        tvGen.setText("生成");
                        tvGen.setClickable(true);
                    }
                }, 100);
            }
        });

        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etInput.setText("");
                tvResult.setText("");
            }
        });

        tvResult.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 获取剪贴板管理器
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                // 创建ClipData对象
                ClipData clip = ClipData.newPlainText("label", tvResult.getText().toString());
                // 设置剪贴板的主剪贴数据
                clipboard.setPrimaryClip(clip);

                Toast.makeText(MainActivity.this, "复制成功", Toast.LENGTH_SHORT).show();
            }
        });
    }
}