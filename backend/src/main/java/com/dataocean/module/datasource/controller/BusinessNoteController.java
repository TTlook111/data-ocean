package com.dataocean.module.datasource.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.dto.BusinessNoteDTO;
import com.dataocean.module.datasource.entity.dto.BusinessNoteStateDTO;
import com.dataocean.module.datasource.entity.query.BusinessNoteQuery;
import com.dataocean.module.datasource.entity.vo.BusinessNoteDetailVO;
import com.dataocean.module.datasource.entity.vo.BusinessNoteRowVO;
import com.dataocean.module.datasource.service.BusinessNoteService;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

/**
 * 企业业务说明、确切历史修订及受控原件下载入口。
 */
@RestController
@RequestMapping("/api/v1/business-notes")
@RequiredArgsConstructor
public class BusinessNoteController {

    /**
     * 资料版本与状态在所属服务处理。
     */
    private final BusinessNoteService notes;

    /**
     * 组合查询当前允许查看的企业说明。
     */
    @GetMapping
    @RequirePermission("business-notes.view")
    public Result<PageVO<BusinessNoteRowVO>> list(BusinessNoteQuery query) {
        return Result.success(notes.list(query));
    }

    /**
     * 当前资料和指定历史修订从同一入口读取。
     */
    @GetMapping("/{id}")
    @RequirePermission("business-notes.view")
    public Result<BusinessNoteDetailVO> detail(
            @PathVariable String id, @RequestParam(required = false) Integer revision) {
        return Result.success(notes.detail(Ids.parse(id), revision));
    }

    /**
     * 提交正文或上传原始说明。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("business-notes.submit")
    public Result<MutationVO> create(
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestPart("metadata") BusinessNoteDTO input,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return Result.success(notes.save(null, command, input, readFiles(files)));
    }

    /**
     * 编辑通过新修订保存，所保留文件沿用原编号。
     */
    @PostMapping(path = "/{id}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("business-notes.replace")
    public Result<MutationVO> edit(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestPart("metadata") BusinessNoteDTO input,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return Result.success(notes.save(Ids.parse(id), command, input, readFiles(files)));
    }

    /**
     * 再次核对资料权限和附件所属的确切修订。
     */
    @GetMapping("/{id}/revisions/{revision}/files/{fileId}")
    @RequirePermission("business-notes.view")
    public ResponseEntity<byte[]> download(
            @PathVariable String id, @PathVariable int revision, @PathVariable String fileId) {
        var attachment = notes.download(Ids.parse(id), revision, Ids.parse(fileId));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(attachment.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(attachment.bytes().length)
                .body(attachment.bytes());
    }

    /**
     * 撤下、恢复及删除各自核权并核对真实引用。
     */
    @PostMapping("/{id}/state-preview")
    @RequirePermission(anyOf = {"business-notes.withdraw", "business-notes.restore", "business-notes.delete"})
    public Result<ImpactPreviewVO> preview(@PathVariable String id, @Valid @RequestBody BusinessNoteStateDTO input) {
        return Result.success(notes.preview(Ids.parse(id), input));
    }

    /**
     * 以原核对依据提交实际资料状态。
     */
    @PostMapping("/{id}/state")
    @RequirePermission(anyOf = {"business-notes.withdraw", "business-notes.restore", "business-notes.delete"})
    public Result<MutationVO> state(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody BusinessNoteStateDTO input) {
        return Result.success(notes.changeState(Ids.parse(id), command, input));
    }

    /**
     * 文件计数先于字节读取，单部件容量受框架限制。
     */
    private List<BusinessNoteService.Attachment> readFiles(List<MultipartFile> files) {
        if (files == null) {
            // 纯文字资料没有附件部件。
            return List.of();
        }
        if (files.size() > BusinessNoteService.MAX_FILES) {
            // 单份资料的文件数量有限。
            throw BizIllegalException.invalid("每份资料最多上传 5 个附件");
        }
        var result = new ArrayList<BusinessNoteService.Attachment>(files.size());
        for (var file : files) {
            // 单个文件保持原始字节和安全名称。
            try {
                result.add(new BusinessNoteService.Attachment(file.getOriginalFilename(), file.getBytes()));
            } catch (IOException error) {
                // 失败只反馈可操作原因。
                throw BizIllegalException.invalid("无法读取上传的附件，请重新选择");
            }
        }
        return result;
    }
}
