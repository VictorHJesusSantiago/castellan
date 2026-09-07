package io.castellan.flow.api.config;

import io.castellan.flow.engine.ProcessDefinitionRepository;
import io.castellan.flow.engine.ProcessEngine;
import io.castellan.flow.engine.ProcessInstanceRepository;
import io.castellan.flow.engine.RuleServiceTaskHandler;
import io.castellan.flow.engine.RuleSetJdbcRegistry;
import io.castellan.flow.engine.TimerRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** Wires {@code flow-engine}'s plain-Java repositories/engine (deliberately independent of
 * Spring — see {@link io.castellan.flow.engine.TimerScheduler}'s own docs on why) into Spring
 * beans, given the {@link JdbcTemplate}/{@link javax.sql.DataSource} Spring Boot's own
 * auto-configuration already provides from {@code application.yml}. */
@Configuration
public class FlowBeanConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ProcessDefinitionRepository processDefinitionRepository(JdbcTemplate jdbc, Clock clock) {
        return new ProcessDefinitionRepository(jdbc, clock);
    }

    @Bean
    public ProcessInstanceRepository processInstanceRepository(JdbcTemplate jdbc) {
        return new ProcessInstanceRepository(jdbc);
    }

    @Bean
    public TimerRepository timerRepository(JdbcTemplate jdbc) {
        return new TimerRepository(jdbc);
    }

    @Bean
    public RuleSetJdbcRegistry ruleSetJdbcRegistry(JdbcTemplate jdbc, Clock clock) {
        return new RuleSetJdbcRegistry(jdbc, clock);
    }

    @Bean
    public RuleServiceTaskHandler ruleServiceTaskHandler(RuleSetJdbcRegistry ruleSets) {
        return new RuleServiceTaskHandler(ruleSets);
    }

    @Bean
    public ProcessEngine processEngine(ProcessDefinitionRepository definitions, ProcessInstanceRepository instances,
                                        TimerRepository timers, RuleServiceTaskHandler handler, Clock clock) {
        return new ProcessEngine(definitions, instances, timers, handler, clock);
    }
}
