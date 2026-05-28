package com.example.multitenant.demo.interfaces;

import com.example.multitenant.demo.application.DemoMessageResult;
import com.example.multitenant.demo.application.port.in.GetDemoMessagesUseCase;
import com.example.multitenant.demo.application.port.in.SaveDemoMessageUseCase;
import com.example.multitenant.demo.application.port.in.command.SaveDemoMessageCommand;
import com.example.multitenant.demo.interfaces.dto.SaveMessageRequest;
import com.example.multitenant.interfaces.web.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DataSource 라우팅 동작 확인용 Demo Controller.
 *
 * <p>사용 흐름:
 * <ol>
 *   <li>POST /api/tenants              — 테넌트 등록</li>
 *   <li>POST /api/demo (X-Tenant-Id)  — 현재 테넌트 DB에 메시지 저장</li>
 *   <li>GET  /api/demo (X-Tenant-Id)  — 현재 테넌트 DB의 메시지 조회</li>
 *   <li>다른 X-Tenant-Id 로 조회 시 데이터가 격리됨을 확인</li>
 * </ol>
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final SaveDemoMessageUseCase saveUseCase;
    private final GetDemoMessagesUseCase getUseCase;

    public DemoController(SaveDemoMessageUseCase saveUseCase,
                          GetDemoMessagesUseCase getUseCase) {
        this.saveUseCase = saveUseCase;
        this.getUseCase  = getUseCase;
    }

    /**
     * 현재 테넌트 DB에 메시지 저장.
     * Header: X-Tenant-Id: {tenantId}
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DemoMessageResult>> save(
            @Valid @RequestBody SaveMessageRequest request) {
        DemoMessageResult result = saveUseCase.save(new SaveDemoMessageCommand(request.content()));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 현재 테넌트 DB의 메시지 전체 조회.
     * Header: X-Tenant-Id: {tenantId}
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<DemoMessageResult>>> findAll() {
        return ResponseEntity.ok(ApiResponse.ok(getUseCase.findAll()));
    }
}
