package com.forward.repository;

import com.forward.entity.FileMessageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileMessageIdRepository extends JpaRepository<FileMessageId, Long> {}
