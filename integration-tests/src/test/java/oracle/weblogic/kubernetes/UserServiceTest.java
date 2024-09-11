package wlstest.functional.wsee.jaxrs.common.apps.restfullpayload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import static java.lang.System.out;

public class UserServiceClient {

    private Client client;
    private WebTarget target;
    private ObjectMapper objectMapper;
    private String adminUrl;

    private String appUri;


    @Before
    public void setUp() {
        client = ClientBuilder.newClient();
        adminUrl = System.getProperty("url","localhost");
        // Define the URL of the RESTful web service
        appUri = adminUrl + "/userservice/users";
        out.println("url  --> " + appUri);


        target = client.target(appUri);
        objectMapper = new ObjectMapper();
    }

    private User createUser(User user) {
        Response response = target.request()
            .post(Entity.entity(user, javax.ws.rs.core.MediaType.APPLICATION_JSON));
        Assert.assertEquals(201, response.getStatus());
        return response.readEntity(User.class);
    }

    private String getChildrenJson(int userId) {
        WebTarget childrenTarget = client.target(appUri + "/" + userId + "/children");
        Response response = childrenTarget.request().get();
        Assert.assertEquals(200, response.getStatus());
        return response.readEntity(String.class);
    }

    @Test
    public void testCreateUserAndComparePayload() {
        // Create a new user
        User newUser = new User();
        newUser.setId(2);
        newUser.setName("Jane Doe");

        User.RestNavMenuNode rootNode = new User.RestNavMenuNode();
        rootNode.setCardIcon("group");
        rootNode.setId("root");
        rootNode.setLabel("Root Node");

        User.RestNavMenuNode childNode = new User.RestNavMenuNode();
        childNode.setCardIcon("directory");
        childNode.setId("PER_HCMPEOPLETOP_FUSE_DIRECTORY");
        childNode.setLabel("Directory");
        childNode.setFocusViewId("/FndOverview");
        childNode.setIcon("/images/qual_directory_16.png");
        childNode.setNodeType("itemNode");
        childNode.setRendered("true");
        childNode.setWebapp("https://pntidzuqy.fusionapps.ocs.oc-test.com:443/hcmUI/");
        rootNode.setRestNavMenuNode(List.of(childNode));

        newUser.setRestNavMenuNodes(List.of(rootNode));
        createUser(newUser);

        // Fetch and print generated payload
        String generatedPayload = getChildrenJson(2);
        System.out.println("Generated Payload:\n" + generatedPayload);

        // Save payload to a text file in formatted view
        try {
            File file = new File("payload.txt");
            if (!file.exists()) {
                file.createNewFile();
            }
            JsonNode jsonNode = objectMapper.readTree(generatedPayload);
            try (FileWriter writer = new FileWriter(file)) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, jsonNode);
            }
            System.out.println("Payload has been written to payload.txt in formatted view.");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Save payload to a text file
        try {
            File file = new File("payload.txt");
            if (!file.exists()) {
                file.createNewFile();
            }
            objectMapper.writeValue(file, objectMapper.readTree(generatedPayload));
            System.out.println("Payload has been written to payload.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Expected payload
        String expectedPayload = "{\n" +
            "  \"restNavMenuNodes\": [\n" +
            "    {\n" +
            "      \"cardIcon\": \"group\",\n" +
            "      \"restNavMenuNode\": [\n" +
            "        {\n" +
            "          \"cardIcon\": \"directory\",\n" +
            "          \"restNavMenuNode\": [],\n" +
            "          \"focusViewId\": \"/FndOverview\",\n" +
            "          \"icon\": \"/images/qual_directory_16.png\",\n" +
            "          \"id\": \"PER_HCMPEOPLETOP_FUSE_DIRECTORY\",\n" +
            "          \"label\": \"Directory\",\n" +
            "          \"nodeType\": \"itemNode\",\n" +
            "          \"rendered\": \"true\",\n" +
            "          \"webapp\": \"https://pntidzuqy.fusionapps.ocs.oc-test.com:443/hcmUI/\"\n" +
            "        }\n" +
            "      ],\n" +
            "      \"focusViewId\": null,\n" +
            "      \"icon\": null,\n" +
            "      \"id\": \"root\",\n" +
            "      \"label\": \"Root Node\",\n" +
            "      \"nodeType\": null,\n" +
            "      \"rendered\": null,\n" +
            "      \"webapp\": null\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        // Compare expected and generated payload
        try {
            JsonNode expectedNode = objectMapper.readTree(expectedPayload);
            JsonNode generatedNode = objectMapper.readTree(generatedPayload);
            Assert.assertEquals(expectedNode, generatedNode);
            System.out.println("Payload comparison passed.");
        } catch (IOException e) {
            e.printStackTrace();
            Assert.fail("Error comparing payloads.");
        }
    }
}
