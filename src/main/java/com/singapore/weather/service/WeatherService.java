package com.singapore.weather.service;

import com.singapore.weather.exception.AllProvidersFailedException;
import com.singapore.weather.exception.CityNotFoundException;
import com.singapore.weather.model.WeatherResult;

public interface WeatherService {

    /**
     * @throws CityNotFoundException      no provider recognises the city
     * @throws AllProvidersFailedException every provider failed and no usable
     *                                     cached value exists
     */
    WeatherResult get(String city);
}
