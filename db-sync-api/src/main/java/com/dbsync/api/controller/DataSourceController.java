package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.datasource.ConnectionTestView;
import com.dbsync.api.dto.datasource.DataSourceRequest;
import com.dbsync.api.dto.datasource.DataSourceView;
import com.dbsync.api.dto.datasource.TableInfoView;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.service.DataSourceService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源 API（T43 / PRD DS-01~05）。
 *
 * <p>权限：读写全部要求 {@link Permission#DS_MANAGE}（ADMIN only，架构 §12.3）。
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    /**
     * 构造。
     *
     * @param dataSourceService 数据源服务
     */
    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 列表。
     *
     * @return 数据源列表
     */
    @GetMapping
    @RequiresPermission(Permission.DS_MANAGE)
    public ApiResponse list() {
        List<DataSourceView> views = dataSourceService.list();
        List<Object> data = new ArrayList<>(views.size());
        for (DataSourceView view : views) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 详情。
     *
     * @param id 数据源 ID
     * @return 数据源
     */
    @GetMapping("/{id}")
    @RequiresPermission(Permission.DS_MANAGE)
    public ApiResponse get(@PathVariable("id") long id) {
        return ApiResponse.ok(dataSourceService.get(id).toMap());
    }

    /**
     * 新建。
     *
     * @param req 请求
     * @return 新建的数据源
     */
    @PostMapping
    @RequiresPermission(Permission.DS_MANAGE)
    @Audited(action = "DS_CREATE", targetType = "DATASOURCE")
    public ApiResponse create(@Valid @RequestBody DataSourceRequest req) {
        return ApiResponse.ok(dataSourceService.create(req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 编辑。
     *
     * @param id  数据源 ID
     * @param req 请求
     * @return 编辑后的数据源
     */
    @PutMapping("/{id}")
    @RequiresPermission(Permission.DS_MANAGE)
    @Audited(action = "DS_UPDATE", targetType = "DATASOURCE")
    public ApiResponse update(@PathVariable("id") long id,
                              @Valid @RequestBody DataSourceRequest req) {
        return ApiResponse.ok(
                dataSourceService.update(id, req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 删除。
     *
     * @param id 数据源 ID
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.DS_MANAGE)
    @Audited(action = "DS_DELETE", targetType = "DATASOURCE")
    public ApiResponse delete(@PathVariable("id") long id) {
        dataSourceService.delete(id, CurrentUser.actorOrAnonymous());
        return ApiResponse.ok();
    }

    /**
     * 连接测试（已保存的数据源）。
     *
     * @param id 数据源 ID
     * @return 测试结果
     */
    @PostMapping("/{id}/test")
    @RequiresPermission(Permission.DS_MANAGE)
    public ApiResponse test(@PathVariable("id") long id) {
        ConnectionTestView view = dataSourceService.test(id);
        return ApiResponse.ok(view.toMap());
    }

    /**
     * 连接测试（未保存的配置）。
     *
     * @param req 请求
     * @return 测试结果
     */
    @PostMapping("/test")
    @RequiresPermission(Permission.DS_MANAGE)
    public ApiResponse testDraft(@Valid @RequestBody DataSourceRequest req) {
        return ApiResponse.ok(dataSourceService.test(req).toMap());
    }

    /**
     * 表清单。
     *
     * @param id     数据源 ID
     * @param schema schema（可空）
     * @return 表清单
     */
    @GetMapping("/{id}/tables")
    @RequiresPermission(Permission.DS_MANAGE)
    public ApiResponse tables(@PathVariable("id") long id,
                              @RequestParam(value = "schema", required = false) String schema) {
        List<TableInfoView> tables = dataSourceService.listTables(id, schema);
        List<Object> data = new ArrayList<>(tables.size());
        for (TableInfoView view : tables) {
            data.add(view);
        }
        return ApiResponse.ok(data);
    }

    /**
     * 单表元数据。
     *
     * @param id     数据源 ID
     * @param table  表名
     * @param schema schema（可空）
     * @return 表元数据
     */
    @GetMapping("/{id}/tables/{table}")
    @RequiresPermission(Permission.DS_MANAGE)
    public ApiResponse table(@PathVariable("id") long id, @PathVariable("table") String table,
                             @RequestParam(value = "schema", required = false) String schema) {
        return ApiResponse.ok(dataSourceService.tableMeta(id, schema, table));
    }
}
