package com.rainnov.modules.inventory.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Slot {

    private int itemId;
    private int count;
    private long acquiredTime;

    public boolean isEmpty() {
        return count <= 0;
    }

    public void clear() {
        this.itemId = 0;
        this.count = 0;
        this.acquiredTime = 0;
    }

    public void addCount(int amount) {
        this.count += amount;
    }

    public void reduceCount(int amount) {
        this.count -= amount;
        if (this.count <= 0) {
            clear();
        }
    }
}
