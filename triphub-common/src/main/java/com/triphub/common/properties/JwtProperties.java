package com.triphub.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "triphub.jwt")
public class JwtProperties {

    /** 管理端秘钥 */
    private String adminSecretKey;

    /** 管理端 token 过期时间（毫秒） */
    private Long adminTtl;

    /** 管理端 token 名称（请求头） */
    private String adminTokenName;

    /** 用户端秘钥 */
    private String userSecretKey;

    /** 用户端 token 过期时间（毫秒） */
    private Long userTtl;

    /** 用户端 token 名称（请求头） */
    private String userTokenName;
}


