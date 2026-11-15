package com.coldchain.api.tenant;

import com.coldchain.api.common.ConflictException;
import com.coldchain.api.common.NotFoundException;
import com.coldchain.api.device.DeviceRepository;
import com.coldchain.api.tenant.TenantDtos.CreateTenantRequest;
import com.coldchain.api.tenant.TenantDtos.UpdateTenantRequest;
import com.coldchain.api.user.AppUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final DeviceRepository devices;
    private final Clock clock;

    public TenantService(TenantRepository tenants, AppUserRepository users, DeviceRepository devices, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.devices = devices;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Tenant> list(Pageable pageable) {
        return tenants.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Tenant get(String id) {
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("Tenant " + id + " not found"));
    }

    @Transactional
    public Tenant create(CreateTenantRequest request) {
        if (tenants.existsById(request.id())) {
            throw new ConflictException("Tenant " + request.id() + " already exists");
        }
        return tenants.save(new Tenant(request.id(), request.name(), clock.instant()));
    }

    @Transactional
    public Tenant update(String id, UpdateTenantRequest request) {
        Tenant tenant = get(id);
        tenant.rename(request.name());
        return tenant; // changes to a managed entity are saved automatically at commit
    }

    @Transactional
    public void delete(String id) {
        Tenant tenant = get(id);
        // Deleting a tenant must not orphan its users or devices.
        if (users.existsByTenantId(id) || devices.existsByTenantId(id)) {
            throw new ConflictException("Tenant " + id + " still has users or devices");
        }
        tenants.delete(tenant);
    }
}
