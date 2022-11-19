package com.juneqqq.config;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/**
 * @author juneqqq
 * @version 1.0
 **/
@EnableSwagger2
@EnableKnife4j
@Configuration
public class Knife4jConfiguration {


    /**
     * 是否允许swagger
     */
    @Value("${swagger.enable:true}")
    private Boolean enableSwagger;

    @Bean(value = "defaultApi2")
    public Docket createRestApi() {
        Docket docket=new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(new ApiInfoBuilder()
                        //.title("swagger-bootstrap-ui-demo RESTful APIs")
                        .description("file-upload")
                        .termsOfServiceUrl("https://juneqqq.github.io/")
                        .contact(new Contact("juneqqq","https://juneqqq.github.io", "1243134432@qq.com"))
                        .version("1.0")
                        .build())
                //分组名称
                .groupName("2.0版本")
                .select()
                //这里指定Controller扫描包路径
                .apis(RequestHandlerSelectors.basePackage("com.juneqqq"))
                .paths(PathSelectors.any())
                .build().enable(enableSwagger);
        return docket;
    }


}