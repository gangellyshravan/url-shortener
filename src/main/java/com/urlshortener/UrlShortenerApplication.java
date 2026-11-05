package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class UrlShortenerApplication {

	public static void main(String[] args) {

		SpringApplication.run(UrlShortenerApplication.class, args);

	}

}
