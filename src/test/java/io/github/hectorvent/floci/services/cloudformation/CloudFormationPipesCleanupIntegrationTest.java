package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Renaming an {@code AWS::Pipes::Pipe} replaces it, and the pipe left under the prior name is
 * deleted after the stack update commits, on the same update cleanup path every replaced resource
 * takes: three attempts, DELETE_IN_PROGRESS then DELETE_COMPLETE or DELETE_FAILED, and an orphan
 * named in the stack's UPDATE_COMPLETE status reason.
 */
@QuarkusTest
class CloudFormationPipesCleanupIntegrationTest {

    @InjectSpy
    PipesService pipesService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void permanentRenameCleanupFailureKeepsCommittedUpdateAndNamesTheOrphanedPipe() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-cleanup-warning-" + suffix;
        String oldName = "pipe-cleanup-old-" + suffix;
        String newName = "pipe-cleanup-new-" + suffix;

        createStack(stackName, template(oldName, suffix));

        Mockito.doThrow(new IllegalStateException("simulated cleanup failure"))
                .when(pipesService)
                .deletePipe(eq(oldName), anyString());

        updateStack(stackName, template(newName, suffix));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString(
                    "The following resource(s) could not be deleted during update cleanup: "
                            + "[MyPipe (" + oldName + ")]."));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + oldName + "</PhysicalResourceId>"))
            .body(containsString("<ResourceStatus>DELETE_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

        verify(pipesService, times(3)).deletePipe(eq(oldName), anyString());
        assertPipe(oldName);
        assertPipe(newName);

        Mockito.doCallRealMethod().when(pipesService).deletePipe(eq(oldName), anyString());
        deleteStack(stackName);
        pipesService.deletePipe(oldName, "us-east-1");
    }

    @Test
    void transientRenameCleanupFailureSucceedsOnThirdAttempt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-cleanup-retry-" + suffix;
        String oldName = "pipe-cleanup-retry-old-" + suffix;
        String newName = "pipe-cleanup-retry-new-" + suffix;

        createStack(stackName, template(oldName, suffix));

        Mockito.doThrow(new IllegalStateException("first cleanup failure"))
                .doThrow(new IllegalStateException("second cleanup failure"))
                .doCallRealMethod()
                .when(pipesService)
                .deletePipe(eq(oldName), anyString());

        updateStack(stackName, template(newName, suffix));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("<StackStatusReason>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + oldName + "</PhysicalResourceId>"))
            .body(containsString(
                    "<ResourceStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>"))
            .body(not(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>")));

        verify(pipesService, times(3)).deletePipe(eq(oldName), anyString());
        assertPipeMissing(oldName);
        assertPipe(newName);

        deleteStack(stackName);
    }

    private static void createStack(String stackName, String templateBody) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void updateStack(String stackName, String templateBody) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertPipe(String pipeName) {
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + pipeName)
        .then()
            .statusCode(200)
            .body("Name", equalTo(pipeName));
    }

    private static void assertPipeMissing(String pipeName) {
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + pipeName)
        .then()
            .statusCode(404);
    }

    private static String template(String pipeName, String suffix) {
        return """
                {
                  "Resources": {
                    "SourceQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "pipe-cleanup-source-%s"}
                    },
                    "TargetQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "pipe-cleanup-target-%s"}
                    },
                    "MyPipe": {
                      "Type": "AWS::Pipes::Pipe",
                      "Properties": {
                        "Name": "%s",
                        "Source": {"Fn::GetAtt": ["SourceQueue", "Arn"]},
                        "Target": {"Fn::GetAtt": ["TargetQueue", "Arn"]},
                        "RoleArn": "arn:aws:iam::000000000000:role/pipe-cleanup-role",
                        "DesiredState": "STOPPED"
                      }
                    }
                  }
                }
                """.formatted(suffix, suffix, pipeName);
    }
}
