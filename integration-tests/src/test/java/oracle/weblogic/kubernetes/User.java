package wlstest.functional.wsee.jaxrs.common.apps.restfullpayload;

import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlRootElement;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@XmlRootElement(name = "user")
public class User {

  private int id;
  private String name;
  private List<RestNavMenuNode> restNavMenuNodes;

  // Getters and Setters

  @JsonProperty("id")
  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  @JsonProperty("name")
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @JsonProperty("restNavMenuNodes")
  public List<RestNavMenuNode> getRestNavMenuNodes() {
    return restNavMenuNodes;
  }

  public void setRestNavMenuNodes(List<RestNavMenuNode> restNavMenuNodes) {
    this.restNavMenuNodes = restNavMenuNodes;
  }

  // Nested class representing RestNavMenuNode
  @XmlRootElement(name = "restNavMenuNode")
  public static class RestNavMenuNode {

    private String cardIcon;
    private List<RestNavMenuNode> restNavMenuNode;
    private String focusViewId;
    private String icon;
    private String id;
    private String label;
    private String nodeType;
    private String rendered;
    private String webapp;

    // Getters and Setters

    @JsonProperty("cardIcon")
    public String getCardIcon() {
      return cardIcon;
    }

    public void setCardIcon(String cardIcon) {
      this.cardIcon = cardIcon;
    }

    @XmlElementRef(type = RestNavMenuNode.class)
    @JsonGetter("restNavMenuNode")
    public List<RestNavMenuNode> getRestNavMenuNode() {
      return restNavMenuNode;
    }

    public void setRestNavMenuNode(List<RestNavMenuNode> restNavMenuNode) {
      this.restNavMenuNode = restNavMenuNode;
    }

    @JsonProperty("focusViewId")
    public String getFocusViewId() {
      return focusViewId;
    }

    public void setFocusViewId(String focusViewId) {
      this.focusViewId = focusViewId;
    }

    @JsonProperty("icon")
    public String getIcon() {
      return icon;
    }

    public void setIcon(String icon) {
      this.icon = icon;
    }

    @JsonProperty("id")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    @JsonProperty("label")
    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    @JsonProperty("nodeType")
    public String getNodeType() {
      return nodeType;
    }

    public void setNodeType(String nodeType) {
      this.nodeType = nodeType;
    }

    @JsonProperty("rendered")
    public String getRendered() {
      return rendered;
    }

    public void setRendered(String rendered) {
      this.rendered = rendered;
    }

    @JsonProperty("webapp")
    public String getWebapp() {
      return webapp;
    }

    public void setWebapp(String webapp) {
      this.webapp = webapp;
    }
  }
}
