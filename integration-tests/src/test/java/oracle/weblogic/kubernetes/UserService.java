package wlstest.functional.wsee.jaxrs.common.apps.restfullpayload;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;

@Path("/users")
public class UserService {

  private static List<User> users = new ArrayList<>();

  static {
    // Adding some mock data
    User user = new User();
    user.setId(1);
    user.setName("John Doe");

    User.RestNavMenuNode node = new User.RestNavMenuNode();
    node.setCardIcon("group");
    node.setId("node1");
    node.setLabel("Home");
    node.setRestNavMenuNode(new ArrayList<>()); // No children initially

    user.setRestNavMenuNodes(List.of(node));
    users.add(user);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public List<User> getUsers() {
    return users;
  }

  @GET
  @Path("/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getUserById(@PathParam("id") int id) {
    for (User user : users) {
      if (user.getId() == id) {
        return Response.ok(user).build();
      }
    }
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response addUser(User user) {
    users.add(user);
    return Response.status(Response.Status.CREATED).entity(user).build();
  }

  @GET
  @Path("/{id}/children")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getChildrenByUserId(@PathParam("id") int id) {
    for (User user : users) {
      if (user.getId() == id) {
        List<User.RestNavMenuNode> children = user.getRestNavMenuNodes().stream()
            .flatMap(node -> node.getRestNavMenuNode().stream())
            .toList();
        return Response.ok(children).build();
      }
    }
    return Response.status(Response.Status.NOT_FOUND).build();
  }
}
