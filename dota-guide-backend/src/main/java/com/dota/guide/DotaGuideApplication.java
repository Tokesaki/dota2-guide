package com.dota.guide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类 —— 整个 Spring Boot 应用的入口
 *
 * 运行原理：
 *   main 方法调用 SpringApplication.run()，Spring Boot 会：
 *   1. 启动内嵌的 Tomcat 服务器（默认端口 8080，可在 application.yml 修改）
 *   2. 自动扫描本类所在包（com.dota.guide）及其子包下的所有组件：
 *      标注了 @RestController、@Service、@Mapper、@Configuration 等注解的类
 *      会被自动创建对象并注册到 Spring 容器（这叫"组件扫描"）
 *   3. 读取 application.yml 中的配置（数据库连接等）
 *
 * 所以后面写的 Controller/Service/Mapper 只要放在 com.dota.guide 包下，
 * 不需要任何额外的"注册"代码，Spring 会自动发现它们 —— 这叫"约定优于配置"。
 */
@EnableScheduling // 开启定时任务（复盘模块的 MetaCollector 每小时采集高分局出装）
@SpringBootApplication // 组合注解 = @Configuration + @EnableAutoConfiguration + @ComponentScan
public class DotaGuideApplication {

    public static void main(String[] args) {
        // 本地软件模式（见 application-desktop.yml 和 desktop 包）：
        //   - 打包好的 exe 通过 JVM 参数 -Ddesktop.mode=true 打开（写在 jpackage 的 --java-options 里）。
        //     不用程序参数传，是因为 jpackage 的启动器只要用户另外带了参数，就会把默认参数整个换掉，
        //     比如双击时带上 --review.meta.enabled=false 就会丢掉 --desktop，退回去连 MySQL；
        //   - 开发时用 java -jar xxx.jar --desktop 也可以。
        if (Boolean.getBoolean("desktop.mode") || java.util.Arrays.asList(args).contains("--desktop")) {
            com.dota.guide.desktop.DesktopLauncher.launch(DotaGuideApplication.class, args);
            return;
        }
        // 启动应用；返回值是 Spring 容器上下文（本项目用不到）
        SpringApplication.run(DotaGuideApplication.class, args);
    }
}
