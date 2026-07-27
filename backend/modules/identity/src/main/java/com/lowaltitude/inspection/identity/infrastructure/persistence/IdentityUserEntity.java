package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("identity_user")
public class IdentityUserEntity {

    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}
