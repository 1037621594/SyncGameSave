package com.example.syncgamesave;

import android.app.Application;
import com.example.syncgamesave.data.SecureConfigStore;
import com.example.syncgamesave.data.SyncRepository;
import com.example.syncgamesave.storage.TreeFiles;
import com.example.syncgamesave.sync.SyncController;

public class SyncApplication extends Application {
    private SyncRepository repository;
    private SecureConfigStore configStore;
    private SyncController controller;

    @Override public void onCreate() {
        super.onCreate();
        repository = new SyncRepository(this);
        configStore = new SecureConfigStore(this);
        controller = new SyncController(repository, configStore, new TreeFiles(this));
    }

    public SyncRepository repository() { return repository; }
    public SecureConfigStore configStore() { return configStore; }
    public SyncController controller() { return controller; }
}
