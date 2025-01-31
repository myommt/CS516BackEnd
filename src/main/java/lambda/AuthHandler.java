package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import model.LoginRequest;
import model.SignupRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import com.google.gson.Gson;
import org.mindrot.jbcrypt.BCrypt;
import software.amazon.awssdk.regions.Region;
import util.JwtUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;

public class AuthHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final String MY_AWS_REGION = System.getenv("MY_AWS_REGION");

    private final DynamoDbClient dynamoDb = DynamoDbClient.builder()
            .region(Region.of(MY_AWS_REGION))
            .build();

    private final S3Client s3Client = S3Client.builder()
            .region(Region.of(MY_AWS_REGION))
            .build();

    private final String USER_TABLE = System.getenv("USER_TABLE");
    private final String S3_BUCKET = System.getenv("S3_BUCKET");
    private final String JWT_SECRET = System.getenv("JWT_SECRET");
    private final Gson gson = new Gson();
    Map<String, String> responseBody = new HashMap<>();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String path = input.getPath();
        String method = input.getHttpMethod();

        if ("/auth/signup".equals(path) && "POST".equals(method)) {
            return handleSignup(input);
        } else if ("/auth/login".equals(path) && "POST".equals(method)) {
            return handleLogin(input);
        } else if ("/auth/upload-image".equals(path) && "POST".equals(method)) {
            return handleImageUpload(input);
        } else if ("/auth/user".equals(path) && "GET".equals(method)) {
            return handleGetUserDetails(input);
        }

        return createResponse(400, "Invalid endpoint");
    }

    private boolean validateSignupRequest(SignupRequest request) {
        return request != null &&
                request.getEmail() != null && !request.getEmail().trim().isEmpty() &&
                request.getPassword() != null && !request.getPassword().trim().isEmpty() &&
                request.getName() != null && !request.getName().trim().isEmpty();
    }

    private boolean validateLoginRequest(LoginRequest request) {
        return request != null &&
                request.getEmail() != null && !request.getEmail().trim().isEmpty() &&
                request.getPassword() != null && !request.getPassword().trim().isEmpty();
    }

    private boolean validateImageUploadRequest(String authHeader, String body) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }

        try {
            JsonObject jsonRequest = JsonParser.parseString(body).getAsJsonObject();
            return jsonRequest.has("image") && !jsonRequest.get("image").getAsString().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private APIGatewayProxyResponseEvent handleSignup(APIGatewayProxyRequestEvent input) {
        try {
            if (input.getBody() == null) {
                responseBody.put("responseCode", "400");
                responseBody.put("responseDesc", "Request body is required");
                return createResponse(400, gson.toJson(responseBody));
            }

            SignupRequest request = gson.fromJson(input.getBody(), SignupRequest.class);

            if (!validateSignupRequest(request)) {
                responseBody.put("responseCode", "400");
                responseBody.put("responseDesc", "Email, password, and name are required fields");
                return createResponse(400, gson.toJson(responseBody));
            }

            // Hash password
            String hashedPassword = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());

            // Store user in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("email", AttributeValue.builder().s(request.getEmail()).build());
            item.put("password", AttributeValue.builder().s(hashedPassword).build());
            item.put("name", AttributeValue.builder().s(request.getName()).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(USER_TABLE)
                    .item(item)
                    .build();

            dynamoDb.putItem(putItemRequest);
            responseBody.put("responseCode", "200");
            responseBody.put("responseDesc", "User created successfully");
            return createResponse(200, gson.toJson(responseBody));
        } catch (Exception e) {
            responseBody.put("responseCode", "500");
            responseBody.put("responseDesc", "Error creating user: " + e.getMessage());
            return createResponse(500, gson.toJson(responseBody));
        }
    }

    private APIGatewayProxyResponseEvent handleLogin(APIGatewayProxyRequestEvent input) {
        try {
            if (input.getBody() == null) {
                responseBody.put("responseCode", "400");
                responseBody.put("responseDesc", "Request body is required");
                return createResponse(400, gson.toJson(responseBody));
            }

            LoginRequest request = gson.fromJson(input.getBody(), LoginRequest.class);

            if (!validateLoginRequest(request)) {
                responseBody.put("responseCode", "400");
                responseBody.put("responseDesc", "Email and password are required fields");
                return createResponse(400, gson.toJson(responseBody));
            }

            // Get user from DynamoDB
            Map<String, AttributeValue> key = new HashMap<>();
            String emailToQuery = request.getEmail().trim();
            key.put("email", AttributeValue.builder().s(emailToQuery).build());

            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(USER_TABLE)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDb.getItem(getItemRequest);

            if (response.hasItem()) {
                String storedHash = response.item().get("password").s();
                if (BCrypt.checkpw(request.getPassword(), storedHash)) {
                    String token = JWT.create()
                            .withSubject(request.getEmail())
                            .sign(Algorithm.HMAC256(JWT_SECRET));

                    responseBody.put("responseCode", "200");
                    responseBody.put("responseDesc", "Success");
                    responseBody.put("token", token);
                    return createResponse(200, gson.toJson(responseBody));
                } else {
                    responseBody.put("responseCode", "404");
                    responseBody.put("responseDesc", "Password mismatch");
                    return createResponse(404, gson.toJson(responseBody));
                }
            } else {
                responseBody.put("responseCode", "401");
                responseBody.put("responseDesc", "Invalid credentials");
                return createResponse(401, gson.toJson(responseBody));
            }

        } catch (Exception e) {
            responseBody.put("responseCode", "500");
            responseBody.put("responseDesc", "Error during login: " + e.getMessage());
            return createResponse(500, gson.toJson(responseBody));
        }
    }

    private APIGatewayProxyResponseEvent handleImageUpload(APIGatewayProxyRequestEvent input) {
        try {
            Map<String, String> headers = input.getHeaders();
            String authHeader = headers != null ? headers.get("Authorization") : null;

            if (!validateImageUploadRequest(authHeader, input.getBody())) {
                responseBody.put("responseCode", "400");
                responseBody.put("responseDesc", "Invalid request. Authorization header and image data are required");
                return createResponse(400, gson.toJson(responseBody));
            }

            // Verify JWT token
            String token = authHeader.replace("Bearer ", "");
            String email = JwtUtil.verifyToken(token);

            // Parse the JSON body
            JsonObject jsonRequest = JsonParser.parseString(input.getBody()).getAsJsonObject();
            String base64Image = jsonRequest.get("image").getAsString();

            // Decode base64 image
            byte[] decodedImage = Base64.getDecoder().decode(base64Image);

            // Generate unique file name
            String key = "profiles/" + UUID.randomUUID().toString() + ".jpg";

            // Upload to S3
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(S3_BUCKET)
                    .key(key)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(decodedImage));

            // Update user profile in DynamoDB
            Map<String, AttributeValue> keys = new HashMap<>();
            keys.put("email", AttributeValue.builder().s(email).build());

            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(USER_TABLE)
                    .key(keys)
                    .attributeUpdates(Map.of("profileImage",
                            AttributeValueUpdate.builder()
                                    .value(AttributeValue.builder().s(key).build())
                                    .action(AttributeAction.PUT)
                                    .build()))
                    .build();

            dynamoDb.updateItem(updateRequest);

            responseBody.put("responseCode", "200");
            responseBody.put("responseDesc", "Image uploaded successfully");
            return createResponse(200, gson.toJson(responseBody));
        } catch (Exception e) {
            responseBody.put("responseCode", "500");
            responseBody.put("responseDesc", "Error uploading image: " + e.getMessage());
            return createResponse(500, gson.toJson(responseBody));
        }
    }

    private APIGatewayProxyResponseEvent handleGetUserDetails(APIGatewayProxyRequestEvent input) {
        try {
            Map<String, String> headers = input.getHeaders();
            if (headers == null || !headers.containsKey("Authorization")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("code", 401);
                errorResponse.put("message", "Authorization header is required");
                return createResponse(401, gson.toJson(errorResponse));
            }

            String authHeader = headers.get("Authorization");
            if (!authHeader.startsWith("Bearer ")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("code", 401);
                errorResponse.put("message", "Invalid authorization format. Must be 'Bearer <token>'");
                return createResponse(401, gson.toJson(errorResponse));
            }

            String token = authHeader.substring(7);
            String email = JwtUtil.verifyToken(token);

            Map<String, AttributeValue> key = new HashMap<>();
            key.put("email", AttributeValue.builder().s(email).build());

            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(USER_TABLE)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDb.getItem(getItemRequest);

            if (response.hasItem()) {
                Map<String, Object> apiResponse = new HashMap<>();
                Map<String, Object> userData = new HashMap<>();

                userData.put("email", response.item().get("email").s());
                userData.put("name", response.item().getOrDefault("name",
                        AttributeValue.builder().s("").build()).s());
                userData.put("profileImage", response.item().getOrDefault("profileImage",
                        AttributeValue.builder().s("").build()).s());

                apiResponse.put("status", "success");
                apiResponse.put("code", 200);
                apiResponse.put("data", userData);

                return createResponse(200, gson.toJson(apiResponse));
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("code", 404);
                errorResponse.put("message", "User not found");

                return createResponse(404, gson.toJson(errorResponse));
            }

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("code", 500);
            errorResponse.put("message", "Error fetching user details: " + e.getMessage());

            return createResponse(500, gson.toJson(errorResponse));
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
        response.setHeaders(headers);

        return response;
    }
}