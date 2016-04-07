package me.chatgame.mobilecg;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import me.chatgame.mobilecg.tug.DownloadListener;
import me.chatgame.mobilecg.tug.Tug;
import me.chatgame.mobilecg.tug.TugTask;
import me.chatgame.mobilecg.tug.util.LogUtil;

/**
 * Created by star on 16/4/7.
 */
public class MainActivity extends Activity implements DownloadListener {

    private RecyclerView recyclerView;
    private DownloadListAdapter adapter;
    private EditText inputEdit;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Tug tug = new Tug.Builder(this).setNeedLog(true).setThreads(2).build();
        Tug.setInstance(tug);
        Tug.getInstance().start();

        recyclerView = (RecyclerView) findViewById(R.id.download_list);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new DownloadListAdapter(this);
        recyclerView.setAdapter(adapter);
        inputEdit = (EditText) findViewById(R.id.url_input);
        View button = findViewById(R.id.download_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = inputEdit.getText().toString();
                if (!TextUtils.isEmpty(url)) {
                    Uri uri = Uri.parse(url);
                    String fileName = uri.getLastPathSegment();
                    LogUtil.logD("filename: %s", fileName);
                    TugTask task = Tug.getInstance().addTask(url, TugTask.FileType.FILE, null, fileName, MainActivity.this);
                    if (task != null) {
                        adapter.addTask(task);
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Pls input valid url", Toast.LENGTH_SHORT).show();
                }
                inputEdit.setText("");
            }
        });

    }

    @Override
    public void onDownloadStart(final TugTask task) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.updateTask(task);
            }
        });
    }

    @Override
    public void onDownloadProgress(final TugTask task) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.updateTask(task);
            }
        });
    }

    @Override
    public void onDownloadSuccess(final TugTask task) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.updateTask(task);
            }
        });
    }

    @Override
    public void onDownloadFail(final TugTask task) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.updateTask(task);
            }
        });
    }

    @Override
    public void onDownloadDeleted(TugTask task) {

    }

    @Override
    public void onDownloadPaused(final TugTask task) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.updateTask(task);
            }
        });
    }
}