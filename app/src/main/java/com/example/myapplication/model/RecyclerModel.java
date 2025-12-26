package com.example.myapplication.model;

public class RecyclerModel {
    private String name;
    private String uuid;

    public RecyclerModel(String name, String uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public String getUuid() {
        return uuid;
    }
}
