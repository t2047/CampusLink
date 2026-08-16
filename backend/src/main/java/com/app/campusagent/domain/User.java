package com.app.campusagent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(nullable = false, unique = true)
    private String email;

    @Setter
    @Column(nullable = false)
    private String password;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.STUDENT;

    /** 用户昵称，可空；展示时回退为 email 前缀（见个人中心需求 §6.1）。 */
    @Setter
    @Column(length = 30)
    private String nickname;

    /** 头像对象键（MinIO），可空；经 /api/users/avatar/{objectKey} 回显。 */
    @Setter
    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public User() {}

    public User(String email, String password) {
        this.email = email;
        this.password = password;
    }
}
