package com.coldchain.api.tenant;

import com.coldchain.api.common.PageResponse;
import com.coldchain.api.common.Sorting;
import com.coldchain.api.tenant.TenantDtos.CreateTenantRequest;
import com.coldchain.api.tenant.TenantDtos.TenantResponse;
import com.coldchain.api.tenant.TenantDtos.UpdateTenantRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;

/** Tenant management: only the platform owner (SUPER_ADMIN). */
@RestController
@RequestMapping("/api/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Tenants", description = "SUPER_ADMIN only")
public class TenantController {

    private static final Set<String> SORT_FIELDS = Set.of("id", "name", "createdAt");

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    @Operation(summary = "List tenants")
    public PageResponse<TenantResponse> list(@ParameterObject @PageableDefault(sort = "id") Pageable pageable) {
        Sorting.requireAllowed(pageable, SORT_FIELDS);
        return PageResponse.of(tenantService.list(pageable), TenantResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a tenant")
    public TenantResponse get(@PathVariable String id) {
        return TenantResponse.from(tenantService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a tenant")
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.create(request);
        return ResponseEntity.created(URI.create("/api/tenants/" + tenant.getId())).body(TenantResponse.from(tenant));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a tenant")
    public TenantResponse update(@PathVariable String id, @Valid @RequestBody UpdateTenantRequest request) {
        return TenantResponse.from(tenantService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a tenant that has no users and no devices")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        tenantService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
