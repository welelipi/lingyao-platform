package com.lingyao.platform.sso;

import com.lingyao.platform.entity.CompanySsoConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * SsoProvider 工厂（按枚举路由到对应实现）
 */
@Component
public class SsoProviderFactory {

    private final Map<CompanySsoConfig.Provider, SsoProvider> providerMap = new EnumMap<>(CompanySsoConfig.Provider.class);

    @Autowired
    public SsoProviderFactory(List<SsoProvider> providers) {
        for (SsoProvider p : providers) {
            providerMap.put(p.getName(), p);
        }
    }

    public SsoProvider getProvider(CompanySsoConfig.Provider provider) {
        SsoProvider p = providerMap.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("不支持的 SSO Provider: " + provider + "（阶段 2 实施 SAML）");
        }
        return p;
    }
}