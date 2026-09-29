package com.openworker.agent.config;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.dialect.JdbcArrayColumns;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.relational.core.dialect.AnsiDialect;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

@Configuration
public class DatabaseConfig extends AbstractJdbcConfiguration {

    private static class AnsiJdbcDialect extends AnsiDialect implements JdbcDialect {
        private static final AnsiJdbcDialect INSTANCE = new AnsiJdbcDialect();

        @Override
        public JdbcArrayColumns getArraySupport() {
            return JdbcArrayColumns.Unsupported.INSTANCE;
        }
    }

    @Bean
    @Override
    public JdbcDialect jdbcDialect(NamedParameterJdbcOperations operations) {
        return AnsiJdbcDialect.INSTANCE;
    }

    @Bean
    public org.springframework.boot.CommandLineRunner schemaMigrator(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                jdbcTemplate.execute("ALTER TABLE sessions ADD COLUMN model_provider VARCHAR(64)");
            } catch (Exception ignored) {
            }
            try {
                jdbcTemplate.execute("ALTER TABLE sessions ADD COLUMN model_name VARCHAR(128)");
            } catch (Exception ignored) {
            }
        };
    }

    @Override
    protected List<?> userConverters() {
        return List.of(
                new LongToInstantConverter(),
                new StringToInstantConverter(),
                new InstantToLongConverter()
        );
    }

    @ReadingConverter
    private static class LongToInstantConverter implements Converter<Long, Instant> {
        @Override
        public Instant convert(Long source) {
            return source != null ? Instant.ofEpochMilli(source) : null;
        }
    }

    @ReadingConverter
    private static class StringToInstantConverter implements Converter<String, Instant> {
        @Override
        public Instant convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            try {
                return Instant.ofEpochMilli(Long.parseLong(source));
            } catch (NumberFormatException e) {
                return Instant.parse(source);
            }
        }
    }

    @WritingConverter
    private static class InstantToLongConverter implements Converter<Instant, Long> {
        @Override
        public Long convert(Instant source) {
            return source != null ? source.toEpochMilli() : null;
        }
    }
}