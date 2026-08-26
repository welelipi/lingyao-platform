package com.lingyao.platform.repository;

import com.lingyao.platform.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByToken(String token);

    List<Invitation> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    @Modifying
    @Query("UPDATE Invitation i SET i.status = 'EXPIRED' WHERE i.status = 'PENDING' AND i.expiresAt < CURRENT_TIMESTAMP")
    int expirePending();
}
