package com.lowaltitude.inspection.identity.application.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 账号和企业接口的返回对象，字段与契约 openapi-identity-account.yaml 1.0.0 的 schema 一一对应。 */
public final class AccountViews {

    private AccountViews() {
    }

    public record SmsCodeSentView(String maskedPhone, int resendAfterSeconds, int expiresInSeconds) {
    }

    public record PhoneChangeTicketView(String changeTicket, int expiresInSeconds) {
    }

    public record PhoneBindingView(String maskedPhone, int signedOutSessions) {
    }

    public record SessionView(UUID sessionId, String deviceName, String platform, String cityName,
                              OffsetDateTime signedInAt, OffsetDateTime lastActiveAt, boolean current) {
    }

    public record SessionListView(List<SessionView> items) {
    }

    public record RevokedCountView(int revoked) {
    }

    public record ClosureConditionView(String code, String title, boolean met, String detail) {
    }

    public record ClosureView(String status, OffsetDateTime requestedAt, OffsetDateTime dueAt,
                              List<ClosureConditionView> conditions) {
    }

    public record DataSectionView(String code, String title, String description, Long count) {
    }

    public record DataExportView(UUID exportId, String status, List<String> sections, OffsetDateTime requestedAt,
                                 OffsetDateTime readyAt, OffsetDateTime expiresAt, Long sizeBytes) {
    }

    public record DataExportOverviewView(String maskedPhone, List<DataSectionView> sections,
                                         List<DataExportView> exports) {
    }

    public record DownloadIntentView(String downloadUrl, String fileName, OffsetDateTime expiresAt) {
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    public record CurrentIdentityView(String type, UUID enterpriseId, String enterpriseName, String role) {
    }

    public record EnterpriseView(UUID enterpriseId, String name, String maskedCreditCode, String cityName,
                                 boolean qualificationReviewed, String role, int memberCount,
                                 OffsetDateTime createdAt) {
    }

    public record EnterpriseListView(CurrentIdentityView identity, List<EnterpriseView> items,
                                     List<ReceivedInvitationView> invitations) {
    }

    public record EnterpriseMemberView(UUID memberId, String displayName, String maskedPhone, String role,
                                       boolean self, OffsetDateTime joinedAt) {
    }

    public record PendingInvitationView(UUID invitationId, String kind, String maskedPhone, String role,
                                        String invitedByName, OffsetDateTime sentAt, OffsetDateTime expiresAt) {
    }

    public record EnterpriseMembersView(UUID enterpriseId, String myRole, List<EnterpriseMemberView> members,
                                        List<PendingInvitationView> invitations) {
    }

    public record CreatedInvitationView(PendingInvitationView invitation, String inviteCode) {
    }

    public record ReceivedInvitationView(UUID invitationId, String kind, String enterpriseName,
                                         boolean qualificationReviewed, String invitedByName, String role,
                                         OffsetDateTime sentAt, OffsetDateTime expiresAt, String status,
                                         String maskedPhone) {
    }

    /** 下单时的付款主体（交易模块经端口取）；closing 表示账号在注销冷静期，不能下单。 */
    public record Payer(String type, UUID organizationId, String name, boolean closing) {
    }
}
