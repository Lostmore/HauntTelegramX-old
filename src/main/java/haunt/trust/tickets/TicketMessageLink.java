package haunt.trust.tickets;

public class TicketMessageLink {
    private final Long userId;
    private final String ticketId;
    private final String username;
    private final TicketService.MessageType messageType;

    public TicketMessageLink(Long userId, String ticketId, String username, TicketService.MessageType messageType) {
        this.userId = userId;
        this.ticketId = ticketId;
        this.username = username;
        this.messageType = messageType;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getUsername() {
        return username;
    }

    public TicketService.MessageType getMessageType() {
        return messageType;
    }
}