package com.llama.hub.mapper;

import com.llama.hub.model.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminUserMapper {

    void insert(AdminUser user);

    AdminUser findByUsername(@Param("username") String username);

    int update(AdminUser user);

    long count();
}
