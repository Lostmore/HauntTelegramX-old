package haunt.trust.tickets;

import lombok.Getter;
import lombok.Setter;

public class TicketState {
    @Setter
    private TicketStep currentStep;
    private String ticketId;
    @Setter
    private String username;
    @Setter
    private String issueDescription;
    @Setter
    private String evidence;
    @Setter
    @Getter
    private String mediaFileId;
    @Setter
    @Getter
    private String mediaType;
    @Setter
    @Getter
    private String mediaCaption;

    public TicketState(TicketStep currentStep, String ticketId) {
        this.currentStep = currentStep;
        this.ticketId = ticketId;
    }

    public TicketStep getCurrentStep() { return currentStep; }

    public String getTicketId() { return ticketId; }

    public String getUsername() { return username; }

    public String getIssueDescription() { return issueDescription; }

    public String getEvidence() { return evidence; }

    public boolean hasMedia() {
        return mediaFileId != null && !mediaFileId.isEmpty();
    }
}