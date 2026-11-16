package ru.practicum.stats.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import ru.practicum.stats.dto.EndpointHitDto;
import ru.practicum.stats.dto.ViewStatsDto;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
public class StatsClientImpl implements StatsClient {
    private final RestClient restClient;
    private final DiscoveryClient discoveryClient;
    private final String statsServiceId;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String HIT = "/hit";
    private static final String STATS = "/stats";

    public StatsClientImpl(DiscoveryClient discoveryClient,
            @Value("${stats-server.id:stats-server}") String statsServiceId) {
        this.discoveryClient = discoveryClient;
        this.statsServiceId = statsServiceId;

        this.restClient = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private ServiceInstance getInstance() {
        try {
            return discoveryClient
                    .getInstances(statsServiceId)
                    .getFirst();
        } catch (Exception e) {
            throw new StatsServerUnavailable(
                    "Ошибка обнаружения адреса сервиса статистики с id: " + statsServiceId,
                    e
            );
        }
    }

    private String buildUrl(String path) {
        return getInstance().getUri().toString() + path;
    }

    @Override
    public void hit(EndpointHitDto hitDto) {
        try {
            restClient.post()
                    .uri(buildUrl(HIT))
                    .body(hitDto)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Не удалось отправить статистику для uri: {}. Ошибка: {}",
                    hitDto.getUri(), e.getMessage());
        }

    }

    @Override
    public List<ViewStatsDto> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, Boolean unique) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(buildUrl(STATS))
                .queryParam("start", start.format(FORMATTER))
                .queryParam("end", end.format(FORMATTER));

        if (uris != null && !uris.isEmpty()) {
            builder.queryParam("uris", uris.toArray());
        }

        if (unique != null) {
            builder.queryParam("unique", unique);
        }

        URI uri = builder.encode().build().toUri();

        return restClient.get()
                .uri(uri)
                .retrieve()
                // Перехват ответа ошибки и проброс нового исключения для дальнейшей обработке в мэйне
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    log.error("Ошибка запроса статистики (4xx): {}", response.getStatusCode());
                    throw new IllegalArgumentException("Некорректный интервал дат для сбора статистики");
                })
                // Перехват ответа ошибки и проброс нового исключения для дальнейшей обработке в мэйне
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    log.error("Сервер статистики недоступен (5xx): {}", response.getStatusCode());
                    throw new IllegalStateException("Сервер статистики временно недоступен");
                })
                .body(new ParameterizedTypeReference<List<ViewStatsDto>>() {
                });
    }
}
