package com.singapore.weather.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class RestClientConfig {

    private RestClientConfig() {
    }

    public static RestClient forProvider(WeatherProperties.Provider provider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(provider.connectTimeout());
        factory.setReadTimeout(provider.readTimeout());

        return RestClient.builder()
                .baseUrl(provider.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
