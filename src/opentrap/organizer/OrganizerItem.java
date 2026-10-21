package opentrap.organizer;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

import java.io.Serializable;

/** One item set aside in the Organizer for later review, with a status and notes. */
public final class OrganizerItem implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String[] STATUSES = {"todo", "in progress", "done", "ignored"};

    public String caption = "";
    public String sourceTool = "";
    public String status = "todo";
    public String notes = "";
    public HttpService service;
    public HttpMessage request;
    public HttpMessage response;
    public final long time = System.currentTimeMillis();

    public OrganizerItem() {}

    public OrganizerItem(HttpService service, HttpMessage request, HttpMessage response, String caption, String tool) {
        this.service = service;
        this.request = request;
        this.response = response;
        this.caption = caption == null ? "" : caption;
        this.sourceTool = tool == null ? "" : tool;
    }

    public String host() { return service == null ? "" : service.host(); }
    public String path() { return request == null ? "" : request.path(); }
}
