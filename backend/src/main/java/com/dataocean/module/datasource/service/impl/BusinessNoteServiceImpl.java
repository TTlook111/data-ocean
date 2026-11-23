package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.TextSearch;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.dto.BusinessNoteDTO;
import com.dataocean.module.datasource.entity.dto.BusinessNoteStateDTO;
import com.dataocean.module.datasource.entity.po.BusinessNotePO;
import com.dataocean.module.datasource.entity.po.BusinessNoteRevisionPO;
import com.dataocean.module.datasource.entity.po.NoteAttachmentPO;
import com.dataocean.module.datasource.entity.query.BusinessNoteQuery;
import com.dataocean.module.datasource.entity.vo.BusinessNoteDetailVO;
import com.dataocean.module.datasource.entity.vo.BusinessNoteRevisionVO;
import com.dataocean.module.datasource.entity.vo.BusinessNoteRowVO;
import com.dataocean.module.datasource.entity.vo.NoteAttachmentVO;
import com.dataocean.module.datasource.mapper.BusinessNoteMapper;
import com.dataocean.module.datasource.mapper.BusinessNoteReadMapper;
import com.dataocean.module.datasource.mapper.BusinessNoteRevisionMapper;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.datasource.mapper.NoteAttachmentMapper;
import com.dataocean.module.datasource.service.BusinessNoteService;
import com.dataocean.module.datasource.service.DatasourceEvents;
import com.dataocean.module.datasource.service.NoteAttachmentCleanup;
import com.dataocean.module.datasource.service.NoteAttachmentStorage;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 企业说明按源留存版本及原文件，变更与审计同事务提交。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BusinessNoteServiceImpl implements BusinessNoteService {

    /**
     * 文件字节上限与平台表限制一致。
     */
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    /**
     * 资料聚合与其当前版本。
     */
    private final BusinessNoteMapper notes;

    /**
     * 不可变的内容修订。
     */
    private final BusinessNoteRevisionMapper revisions;

    /**
     * 原件元数据及其存储定位。
     */
    private final NoteAttachmentMapper attachments;

    /**
     * 原件使用当前环境配置的私有 OSS 存储。
     */
    private final NoteAttachmentStorage storage;

    /**
     * 上传崩溃回收预留及资料删除后的原件清理。
     */
    private final NoteAttachmentCleanup cleanup;

    /**
     * 在独立预留提交后开启资料写入事务。
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * 修订、原文件关系和历史引用。
     */
    private final BusinessNoteReadMapper reads;

    /**
     * 依所属源约束新建和恢复入口。
     */
    private final DatasourceRecordMapper sources;

    /**
     * 读取可披露的提交人。
     */
    private final UserMapper users;

    /**
     * 公共同事务审计和命令防重。
     */
    private final MutationService mutations;

    /**
     * 保留动作影响、凭据及权限校验。
     */
    private final ReviewService reviews;

    /**
     * 当前资料操作授权。
     */
    private final PermissionService permissions;

    /**
     * 同事务审计。
     */
    private final AuditService audit;

    /**
     * 受影响引用需由后续消费者回读并处理。
     */
    private final DatasourceEvents events;

    /**
     * 分页查询时统计和返回使用相同资料范围。
     */
    @Override
    public PageVO<BusinessNoteRowVO> list(BusinessNoteQuery query) {
        return PageVO.from(
                reads.page(
                        query.toFixedOrderMpPage(OrderItem.desc("n.updated_at"), OrderItem.desc("n.id")),
                        Ids.nullable(query.getSourceId()),
                        query.getState(),
                        pattern(query.getKeyword()),
                        pattern(query.getAuthor())),
                this::row);
    }

    /**
     * 指定数字修订时严格按原内容和原附件回读。
     */
    @Override
    public BusinessNoteDetailVO detail(long id, Integer requestedRevision) {
        var note = requireNote(id);
        var revision = requireRevision(id, requestedRevision == null ? note.getRevision() : requestedRevision);
        var history = revisions.selectList(Wrappers.<BusinessNoteRevisionPO>lambdaQuery()
                .select(BusinessNoteRevisionPO::getRevision)
                .eq(BusinessNoteRevisionPO::getNoteId, id)
                .orderByDesc(BusinessNoteRevisionPO::getRevision));
        var content = new BusinessNoteRevisionVO(
                Long.toString(revision.getId()),
                revision.getRevision(),
                revision.getTitle(),
                revision.getBody(),
                Times.instant(revision.getCreatedAt()),
                person(revision.getChangedBy()),
                reads.attachments(revision.getId()).stream()
                        .map(file -> new NoteAttachmentVO(file.getId().toString(), file.getName(), file.getByteSize()))
                        .toList());
        return new BusinessNoteDetailVO(
                row(note),
                content,
                history.stream().map(BusinessNoteRevisionPO::getRevision).toList(),
                note.getStateReason());
    }

    /**
     * 新增或编辑先登记 OSS 上传预留，再在命令事务中提交原件关系、修订和资料状态。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MutationVO save(Long id, String command, BusinessNoteDTO input, List<Attachment> files) {
        var commandFiles = files.stream().map(this::prepareFile).toList();
        var retained = input.getKeptAttachmentIds() == null
                ? List.<Long>of()
                : input.getKeptAttachmentIds().stream()
                        .map(Ids::parse)
                        .distinct()
                        .toList();
        if (retained.size() + files.size() > MAX_FILES || id == null && !retained.isEmpty()) {
            // 无法跨资料引用原文件，上传内容按总数限制。
            throw BizIllegalException.invalid("每份资料最多保留 5 个附件");
        }
        String body = Objects.toString(input.getBody(), "").strip();
        if (body.isEmpty() && retained.isEmpty() && files.isEmpty()) {
            // 至少提交确切正文或原文件。
            throw BizIllegalException.invalid("请填写说明正文或选择附件");
        }
        String operation = id == null ? "business-notes.submit" : "business-notes.replace";
        var signatures = commandFiles.stream()
                .map(file -> List.of(file.metadata().getName(), file.metadata().getDigest()))
                .toList();
        var newFiles = commandFiles.stream().map(PreparedAttachment::metadata).toList();
        if (!newFiles.isEmpty()) {
            // 预留须独立提交，进程中断也能回收未经资料事务确认的对象。
            newFiles.forEach(storage::assignLocation);
            cleanup.reserveUploads(newFiles);
        }

        Supplier<MutationVO> mutation = () -> mutations.execute(
                operation,
                command,
                id == null ? "new" : "business-note:" + id,
                List.of(input, signatures),
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 在来源行锁下检查已退役状态及当前资料版本。
                    var note = id == null ? createNote(input) : requireNote(id);
                    var source = sources.lock(note.getSourceId());
                    if (id != null) {
                        // 等待源锁后核对当前资料版本，防止旧快照覆盖并发写入。
                        note = lockNote(id);
                    }
                    if ("RETIRED".equals(source.getLifecycle()) || !"ACTIVE".equals(note.getState())) {
                        // 退役资料只读，撤下资料须先恢复。
                        throw BizIllegalException.state("当前资料不可编辑");
                    }
                    var title = input.getTitle().strip();
                    String beforeTitle = null;
                    Long beforeRevision = null;
                    List<NoteAttachmentPO> kept = List.of();
                    boolean substantive = true;
                    if (id != null) {
                        // 只允许从正在编辑的确切当前修订保留附件。
                        checkVersion(note, input.getExpectedVersion());
                        var current = requireRevision(id, note.getRevision());
                        beforeRevision = current.getId();
                        var existing = reads.attachments(current.getId());
                        kept = existing.stream()
                                .filter(file -> retained.contains(file.getId()))
                                .toList();
                        if (kept.size() != retained.size()) {
                            // 旧版或其他资料的附件不能直接附到当前表单。
                            throw BizIllegalException.invalid("所保留附件不属于当前资料修订");
                        }
                        beforeTitle = current.getTitle();
                        substantive =
                                !body.equals(current.getBody()) || !files.isEmpty() || kept.size() != existing.size();
                        if (!substantive && beforeTitle.equals(title)) {
                            // 相同输入沿用当前修订。
                            return new MutationVO(command, "business_note", id.toString(), note.getVersion());
                        }
                        note.setRevision(note.getRevision() + 1);
                        note.setVersion(note.getVersion() + 1);
                    }
                    note.setTitle(title);
                    note.setUpdatedAt(reads.now());
                    if (id == null) {
                        // 先取得资料的稳定关联键。
                        notes.insert(note);
                    } else {
                        // 状态变更与内容修订是不同口径。
                        notes.updateById(note);
                    }
                    long afterRevision = writeRevision(note, body, kept, commandFiles);
                    if (beforeRevision != null && substantive && reads.references(id) > 0) {
                        // 发布受引用的实际内容修订变化，后续按原依赖只更新受影响条目。
                        events.businessNote(
                                note.getSourceId(),
                                id,
                                note.getRevision(),
                                note.getVersion(),
                                beforeRevision,
                                afterRevision);
                    }
                    return record(
                            note,
                            operation,
                            command,
                            null,
                            new ChangeVO("revision", "资料修订", beforeTitle, title + " · 第 " + note.getRevision() + " 版"));
                });

        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            // 命令防重或业务提交成功后，同一事务撤销本次各对象的回收预留。
            var result = mutation.get();
            cleanup.completeUploads(newFiles);
            return result;
        });
    }

    /**
     * 下载通道同时绑定资料、修订、附件和当前资料查看资格。
     */
    @Override
    public Attachment download(long id, int revision, long attachment) {
        requireNote(id);
        var file = reads.download(id, revision, attachment);
        if (file == null) {
            // 直接访问附件编号不能解除修订归属约束。
            throw BizIllegalException.missing();
        }
        return new Attachment(file.getName(), storage.download(file));
    }

    /**
     * 按当前实际引用和资料状态核对目标状态。
     */
    @Override
    public ImpactPreviewVO preview(long id, BusinessNoteStateDTO input) {
        permissions.require(operation(input.getAction()));
        var note = requireNote(id);
        if ("RETIRED".equals(sources.selectById(note.getSourceId()).getLifecycle())) {
            // 退役后资料状态不能变更。
            throw BizIllegalException.state("已退役数据源的资料仅供回看");
        }
        checkVersion(note, input.getExpectedVersion());
        int references = reads.references(id);
        checkState(note, input, references);
        var next = target(input.getAction());
        return reviews.preview(
                input.getAction(),
                Long.toString(id),
                note.getVersion(),
                input,
                List.of(new ChangeVO("state", "资料状态", label(note.getState()), label(next))),
                references == 0 ? List.of() : List.of(new ImpactCountVO("引用记录", references)),
                "DELETED".equals(next) ? List.of("将永久删除正文及附件。") : List.of());
    }

    /**
     * 状态动作不递增内容修订；删除前核验所有历史及在途引用。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO changeState(long id, String command, BusinessNoteStateDTO input) {
        String operation = operation(input.getAction());
        return mutations.execute(
                operation, command, "business-note:" + id, input, input.getExpectedVersion(), false, false, () -> {
                    // 锁定来源后对资料加排他锁，保存内容和引用登记均遵循相同次序。
                    var original = requireNote(id);
                    var source = sources.lock(original.getSourceId());
                    var note = lockNote(id);
                    if ("RETIRED".equals(source.getLifecycle())) {
                        // 退役资料只供历史回看。
                        throw BizIllegalException.state("已退役数据源的资料仅供回看");
                    }
                    checkVersion(note, input.getExpectedVersion());
                    int references = reads.references(id);
                    checkState(note, input, references);
                    if (input.getReviewToken() == null || input.getReviewToken().isBlank()) {
                        // 每次状态变更均需明确核对。
                        throw BizIllegalException.invalid("请先核对资料操作");
                    }
                    reviews.verify(
                            input.getAction(), Long.toString(id), note.getVersion(), input, input.getReviewToken());
                    String before = note.getState();
                    String next = target(input.getAction());
                    if ("WITHDRAWN".equals(next)) {
                        note.setStateReason(input.getReason().strip());
                    }
                    if ("DELETED".equals(next)) {
                        // 清理任务和资料删除一同提交，后台只能领取已提交的对象。
                        cleanup.queueDeletion(id);
                        revisions.delete(Wrappers.<BusinessNoteRevisionPO>lambdaQuery()
                                .eq(BusinessNoteRevisionPO::getNoteId, id));
                        attachments.delete(
                                Wrappers.<NoteAttachmentPO>lambdaQuery().eq(NoteAttachmentPO::getNoteId, id));
                    }
                    note.setState(next);
                    note.setVersion(note.getVersion() + 1);
                    note.setUpdatedAt(reads.now());
                    notes.updateById(note);
                    if ("WITHDRAWN".equals(next) && references > 0) {
                        // 确切资料状态及同版本治理影响在原写事务内生效。
                        long revision = requireRevision(id, note.getRevision()).getId();
                        events.businessNote(
                                note.getSourceId(), id, note.getRevision(), note.getVersion(), revision, null);
                    }
                    return record(
                            note,
                            operation,
                            command,
                            input.getReason(),
                            new ChangeVO("state", "资料状态", label(before), label(next)));
                });
    }

    /**
     * 解析三个明示状态动作所需的权限编码。
     */
    private String operation(String action) {
        return switch (action) {
                // 当前处理使用现有权限目录。
            case "WITHDRAW" -> "business-notes.withdraw";
            case "RESTORE" -> "business-notes.restore";
            case "DELETE" -> "business-notes.delete";
            default -> throw BizIllegalException.invalid("不支持的资料操作");
        };
    }

    /**
     * 三种操作的实际状态转换。
     */
    private String target(String action) {
        return switch (action) {
                // 上下架不新增内容修订。
            case "WITHDRAW" -> "WITHDRAWN";
            case "RESTORE" -> "ACTIVE";
            case "DELETE" -> "DELETED";
            default -> throw BizIllegalException.invalid("不支持的资料操作");
        };
    }

    /**
     * 预览与执行使用相同的状态与引用约束。
     */
    private void checkState(BusinessNotePO note, BusinessNoteStateDTO input, int references) {
        var target = target(input.getAction());
        if ("DELETED".equals(note.getState()) || target.equals(note.getState())) {
            // 同一内容状态不能重复提交。
            throw BizIllegalException.state("当前资料状态不支持此操作");
        }
        if ("WITHDRAWN".equals(target)
                && (input.getReason() == null || input.getReason().isBlank())) {
            // 技术人员可在历史回看实际撤下原因。
            throw BizIllegalException.invalid("请填写撤下原因");
        }
        if ("DELETED".equals(target) && references > 0) {
            // 历史及在途引用均保护原资料。
            throw BizIllegalException.state("资料已有引用，不能删除");
        }
    }

    /**
     * 新建资料只填写归属、真实作者和初始状态。
     */
    private BusinessNotePO createNote(BusinessNoteDTO input) {
        var source = sources.lock(Ids.parse(input.getSourceId()));
        if (source == null || !"ENABLED".equals(source.getLifecycle())) {
            // 接入完成的真实来源才受理新资料。
            throw BizIllegalException.state("请选择已启用数据源");
        }
        var note = new BusinessNotePO();
        note.setAuthorId(UserContext.getUser());
        note.setSourceId(source.getId());
        note.setState("ACTIVE");
        note.setRevision(1);
        note.setVersion(1);
        return note;
    }

    /**
     * 保存当前版本的新正文和准确附件集合。
     */
    private long writeRevision(
            BusinessNotePO note, String body, List<NoteAttachmentPO> kept, List<PreparedAttachment> added) {
        var revision = new BusinessNoteRevisionPO();
        revision.setNoteId(note.getId());
        revision.setRevision(note.getRevision());
        revision.setTitle(note.getTitle());
        revision.setBody(body);
        revision.setChangedBy(UserContext.getUser());
        revisions.insert(revision);
        var all = new ArrayList<>(kept);
        for (var original : added) {
            // 先锁住回收预留，再上传原件；旧修订仍可复用各自已保存的附件。
            var file = original.metadata();
            cleanup.lockUpload(file);
            storage.upload(file, original.bytes());
            file.setNoteId(note.getId());
            attachments.insert(file);
            all.add(file);
        }
        all.forEach(file -> reads.link(note.getId(), revision.getId(), file.getId()));
        return revision.getId();
    }

    /**
     * 按文件名、大小及文件格式基本特征验证原文件。
     */
    private PreparedAttachment prepareFile(Attachment upload) {
        String name = Objects.toString(upload.name(), "").strip();
        if (name.isEmpty()
                || name.length() > 180
                || name.contains("/")
                || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            // 拒绝可能改变文件名语义的路径与控制字符。
            throw BizIllegalException.invalid("附件文件名不支持");
        }
        byte[] bytes = upload.bytes();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            // 单个附件必须非空且不超过大小上限。
            throw BizIllegalException.invalid("附件必须非空且不超过 20 MB");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!validType(extension, bytes)) {
            // 原件仅保留为附件下载，不以用户声明的 MIME 放行浏览器内联渲染。
            throw BizIllegalException.invalid("附件类型不支持或内容与扩展名不符");
        }
        var row = new NoteAttachmentPO();
        row.setName(name);
        row.setByteSize(bytes.length);
        // 防重摘要包含完整文件内容，不把原始字节放进审计和命令记录。
        row.setDigest(NoteAttachmentStorage.sha256(bytes));
        return new PreparedAttachment(row, bytes);
    }

    /**
     * 当前提交中已通过校验的文件，原件字节只供本次上传使用。
     *
     * @param metadata 随修订落库的文件摘要及定位
     * @param bytes 待上传的实际原件
     */
    private record PreparedAttachment(NoteAttachmentPO metadata, byte[] bytes) {}

    /**
     * 在不解压或渲染用户文件的情况下执行允许类型的基础格式校验。
     */
    private boolean validType(String extension, byte[] bytes) {
        if (Set.of("txt", "md", "csv").contains(extension)) {
            // 文本原件要求可严格按 UTF-8 解码且不含二进制控制字符。
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(ByteBuffer.wrap(bytes))
                        .chars()
                        .noneMatch(value -> Character.isISOControl(value) && value != 9 && value != 10 && value != 13);
            } catch (CharacterCodingException invalid) {
                return false;
            }
        }
        var magic = extension.equals("pdf")
                ? "%PDF-".getBytes(StandardCharsets.US_ASCII)
                : Set.of("docx", "xlsx").contains(extension) ? new byte[] {80, 75, 3, 4} : new byte[0];
        return magic.length > 0 && java.util.Arrays.equals(magic, java.util.Arrays.copyOf(bytes, magic.length));
    }

    /**
     * 资料列表的状态与确切附件数与当前修订一致。
     */
    private BusinessNoteRowVO row(BusinessNotePO note) {
        var content = requireRevision(note.getId(), note.getRevision());
        return new BusinessNoteRowVO(
                note.getId().toString(),
                note.getSourceId().toString(),
                sources.selectById(note.getSourceId()).getName(),
                note.getTitle(),
                person(note.getAuthorId()),
                note.getState(),
                note.getVersion(),
                note.getRevision(),
                reads.attachments(content.getId()).size(),
                reads.references(note.getId()),
                Times.instant(note.getUpdatedAt()));
    }

    /**
     * 只允许回看尚存在的资料。
     */
    private BusinessNotePO requireNote(long id) {
        var note = notes.selectById(id);
        if (note == null || "DELETED".equals(note.getState())) {
            // 同一结果用于未知或已删除记录。
            throw BizIllegalException.missing();
        }
        return note;
    }

    /**
     * 源行锁在先，资料读取使用当前排他版本。
     */
    private BusinessNotePO lockNote(long id) {
        var note = notes.selectOne(Wrappers.<BusinessNotePO>lambdaQuery()
                .eq(BusinessNotePO::getId, id)
                .last("FOR UPDATE"));
        if (note == null || "DELETED".equals(note.getState())) {
            // 并发关闭后只保留业务审计。
            throw BizIllegalException.missing();
        }
        return note;
    }

    /**
     * 目标修订必须属于其资料。
     */
    private BusinessNoteRevisionPO requireRevision(long id, int revision) {
        var result = revisions.selectOne(Wrappers.<BusinessNoteRevisionPO>lambdaQuery()
                .eq(BusinessNoteRevisionPO::getNoteId, id)
                .eq(BusinessNoteRevisionPO::getRevision, revision));
        if (result == null) {
            // 不用当前版替换缺失的历史修订。
            throw BizIllegalException.missing();
        }
        return result;
    }

    /**
     * 强制所有变更使用当前版本。
     */
    private void checkVersion(BusinessNotePO note, Integer version) {
        if (!Objects.equals(note.getVersion(), version)) {
            // 回读后再编辑，避免覆盖另一人更新。
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 披露提交人实际账号的最小公开字段。
     */
    private UserOptionVO person(long id) {
        var user = users.selectById(id);
        return new UserOptionVO(
                user.getId().toString(),
                user.getUsername(),
                user.getDisplayName(),
                user.getLifecycle(),
                user.getVersion(),
                false);
    }

    /**
     * 状态动作给审计及核对使用的业务名称。
     */
    private String label(String state) {
        return switch (state) {
                // 已删除状态仅在审计保留。
            case "ACTIVE" -> "有效";
            case "WITHDRAWN" -> "已撤下";
            case "DELETED" -> "已删除";
            default -> state;
        };
    }

    /**
     * 保留公共查询的字面匹配。
     */
    private String pattern(String value) {
        return value == null || value.isBlank() ? null : TextSearch.pattern(value);
    }

    /**
     * 同事务审计仅保存实际元数据和状态，不记录正文、原文件及秘密。
     */
    private MutationVO record(BusinessNotePO note, String operation, String command, String reason, ChangeVO change) {
        String key = UUID.fromString(command).toString();
        var target = note.getId().toString();
        audit.record(AuditWriteDTO.builder()
                .actionCode(operation)
                .targetType("business_note")
                .targetId(target)
                .targetName(note.getTitle())
                .reason(reason)
                .changes(List.of(change))
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(mutations.auditSecurityVersion(false))
                .commandId(key)
                .build());
        return new MutationVO(key, "business_note", target, note.getVersion());
    }
}
