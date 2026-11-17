package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.po.GuardPO;


public interface GuardMapper {
    @Select("SELECT * FROM iam_security_guard WHERE id=1 FOR UPDATE") GuardPO lock();
    @Select("SELECT * FROM iam_security_guard WHERE id=1 FOR SHARE") GuardPO shared();
    @Select("SELECT * FROM iam_security_guard WHERE id=1") GuardPO current();
    @Update("UPDATE iam_security_guard SET security_version=security_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=1") int increment();
    @Update("UPDATE iam_security_guard SET bootstrap_completed=1,updated_at=UTC_TIMESTAMP(3) WHERE id=1") int completeBootstrap();
    @Select("SELECT LAST_INSERT_ID()") long lastId();
}
