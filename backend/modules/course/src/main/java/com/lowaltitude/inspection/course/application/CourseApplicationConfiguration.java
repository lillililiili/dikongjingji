package com.lowaltitude.inspection.course.application;

import java.time.Clock;

import com.lowaltitude.inspection.course.application.ops.CourseOpsService;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 课程（S5-5）。交易付款、活动、机构由启动模块接上（CoursePorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = CourseMapper.class)
public class CourseApplicationConfiguration {

    @Bean
    CourseService courseService(
            CourseMapper mapper,
            CoursePorts.Payments payments,
            CoursePorts.Activities activities,
            CoursePorts.Organizations organizations
    ) {
        return new CourseService(mapper, payments, activities, organizations, Clock.systemUTC());
    }

    @Bean
    CourseOpsService courseOpsService(
            CourseMapper courses,
            CourseOpsMapper mapper,
            CoursePorts.Organizations organizations,
            CoursePorts.Payments payments,
            CoursePorts.Activities activities
    ) {
        return new CourseOpsService(courses, mapper, organizations, payments, activities, Clock.systemUTC());
    }
}
