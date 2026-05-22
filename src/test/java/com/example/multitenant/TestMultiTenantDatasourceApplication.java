package com.example.multitenant;

import org.springframework.boot.SpringApplication;

public class TestMultiTenantDatasourceApplication {

	public static void main(String[] args) {
		SpringApplication.from(MultiTenantDatasourceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
