package haunt.trust.tickets;

import java.time.LocalDateTime;

public class TicketData {
    private final String ticketId;
    private final Long userId;
    private final String username;
    private final String issueDescription;
    private final String evidence;
    private final LocalDateTime createdAt;

    public TicketData(String ticketId, Long userId, String username,
                      String issueDescription, String evidence, LocalDateTime createdAt) {
        this.ticketId = ticketId;
        this.userId = userId;
        this.username = username;
        this.issueDescription = issueDescription;
        this.evidence = evidence;
        this.createdAt = createdAt;
    }

    public String getTicketId() { return ticketId; }
    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getIssueDescription() { return issueDescription; }
    public String getEvidence() { return evidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
