package com.navblind.server.service;

import com.navblind.server.dto.DestinationDto.*;
import com.navblind.server.entity.Destination;
import com.navblind.server.entity.User;
import com.navblind.server.integration.NominatimClient;
import com.navblind.server.repository.DestinationRepository;
import com.navblind.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

//사용자의 즐겨찾기 장소(목적지)를 관리하고, 새로운 장소를 CRUD하는 로직을 담당

@Service
@RequiredArgsConstructor
@Slf4j
public class DestinationService {

    private final NominatimClient nominatimClient;
    private final DestinationRepository destinationRepository;
    private final UserRepository userRepository;

    //장소를 찾기를 요청하면 근처 지역을 한국어로 정리된 SearchResult 리스트로 반환
    public List<SearchResult> searchPlaces(String query, Double lat, Double lng, int limit) {
        log.info("Searching places for query: '{}' near ({}, {})", query, lat, lng);
        return nominatimClient.search(query, lat, lng, limit);
    }

    //사용자의 목적지 목록을 보여주는데, label이 있으면 label로 필터링
    //@Transactional(readOnly = true) -> 읽기 전용 트랜잭션으로 성능 향상
    @Transactional(readOnly = true)
    public List<DestinationResponse> getSavedDestinations(UUID userId, String label) {
        List<Destination> destinations;
        if (label != null && !label.isEmpty()) {
            destinations = destinationRepository.findByUserIdAndLabelOrderByUseCountDesc(userId, label);
        } else {
            destinations = destinationRepository.findByUserIdOrderByUseCountDesc(userId);
        }

        return destinations.stream()
                .map(this::toResponse)
                .toList();
    }

    //해당 장소를 즐겨찾기에 추가하는 기능을 수행
    //@Transactional: 저장 도중 에러 나면 해당 트랜잭션을 롤백시켜 데이터 일관성 유지
    @Transactional
    public DestinationResponse createDestination(UUID userId, CreateDestinationRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Destination destination = Destination.builder()
                .user(user)
                .name(request.getName())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .address(request.getAddress())
                .label(request.getLabel())
                .build();

        destination = destinationRepository.save(destination);
        log.info("Created destination {} for user {}", destination.getId(), userId);

        return toResponse(destination);
    }

    //즐겨 찾기 이름이나 라벨을 수정하는 기능을 가짐
    @Transactional
    public DestinationResponse updateDestination(UUID userId, UUID destinationId, UpdateDestinationRequest request) {
        Destination destination = destinationRepository.findByIdAndUserId(destinationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Destination not found: " + destinationId));

        if (request.getName() != null) {
            destination.setName(request.getName());
        }
        if (request.getLabel() != null) {
            destination.setLabel(request.getLabel());
        }

        destination = destinationRepository.save(destination);
        log.info("Updated destination {}", destinationId);

        return toResponse(destination);
    }

    //해당 즐겨찾기를 삭제
    @Transactional
    public void deleteDestination(UUID userId, UUID destinationId) {
        Destination destination = destinationRepository.findByIdAndUserId(destinationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Destination not found: " + destinationId));

        destinationRepository.delete(destination);
        log.info("Deleted destination {}", destinationId);
    }

    //네비게이션 도착 시 호출되며 해당 목적지의 useCount을 1 증가시킴
    @Transactional
    public void incrementUseCount(UUID userId, UUID destinationId) {
        destinationRepository.findByIdAndUserId(destinationId, userId)
                .ifPresent(dest -> {
                    dest.incrementUseCount();
                    destinationRepository.save(dest);
                });
    }

    private DestinationResponse toResponse(Destination destination) {
        return DestinationResponse.builder()
                .id(destination.getId())
                .name(destination.getName())
                .latitude(destination.getLatitude())
                .longitude(destination.getLongitude())
                .address(destination.getAddress())
                .label(destination.getLabel())
                .useCount(destination.getUseCount())
                .createdAt(destination.getCreatedAt() != null ? destination.getCreatedAt().toString() : null)
                .build();
    }
}
