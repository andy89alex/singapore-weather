package com.singapore.weather.domain;

public interface WeatherService {

    /**
     * @throws CityNotFoundException      no provider recognises the city
     * @throws AllProvidersFailedException every provider failed and no usable
     *                                     cached value exists
     */
    WeatherResult get(String city);
}
