package controllers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSResponse;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import views.html.index;
import views.html.setup;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Application extends Controller {
    @Inject
    private Force force;

    //Si on récupère le consumerKey et le consumerSecret, on retourne TRUE
    private boolean isSetup() {
        try {
            force.consumerKey();
            force.consumerSecret();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    //On récupère la requête, si elle est sécurisée, on retourne l'URL avec un HTTPS au début
    private String oauthCallbackUrl(Http.Request request) {
        return (request.secure() ? "https" : "http") + "://" + request.host();
    }

    //On récupère un code
    public CompletionStage<Result> index(String code) {
        //On vérifie que l'on possède les clés
        if (isSetup()) {
            //Si le code (Token d'autorisation) n'existe pas encore
            if (code == null) {
                // start oauth (On récupère l'ID Client (consumerKey), l'url de redirection (Callback URL), et l'URL de l'organisation
                final String url = "https://axione-sso--devforce.cs106.my.salesforce.com/services/oauth2/authorize?response_type=code" +
                        "&client_id=" + force.consumerKey() +
                        "&redirect_uri=" + oauthCallbackUrl(request());
                return CompletableFuture.completedFuture(redirect(url));
            //Si le code existe déjà (On a déjà le Token d'autorisation) on envoie une requête pour récupérer le Token d'authentification
            } else {
                return force.getToken(code, oauthCallbackUrl(request()));
            }
        // Si l'on ne possède pas encore les clés, on déclence l'initialisation de l'application
        } else {
            return CompletableFuture.completedFuture(redirect(routes.Application.setup()));
        }
    }

    public Result setup() {
        if (isSetup()) {
            return redirect(routes.Application.index(null));
        } else {
            final String maybeHerokuAppName = request().host().split(".herokuapp.com")[0].replaceAll(request().host(), "");
            return ok(setup.render(maybeHerokuAppName));
        }
    }


    @Singleton
    public static class Force {
        @Inject
        WSClient ws;

        @Inject
        Config config;

        String consumerKey() {
            return config.getString("consumer.key");
        }

        String consumerSecret() {
            return config.getString("consumer.secret");
        }

        CompletionStage<AuthInfo> getToken(String code, String redirectUrl) {
            final CompletionStage<WSResponse> responsePromise = ws.url("https://axione-sso--devforce.cs106.my.salesforce.com/services/oauth2/token")
                    .addQueryParameter("grant_type", "authorization_code")
                    .addQueryParameter("code", code)
                    .addQueryParameter("client_id", consumerKey())
                    .addQueryParameter("client_secret", consumerSecret())
                    .addQueryParameter("redirect_uri", redirectUrl)
                    .execute(Http.HttpVerbs.POST);

            return responsePromise.thenCompose(response -> {
                final JsonNode jsonNode = response.asJson();

                if (jsonNode.has("error")) {
                    CompletableFuture<AuthInfo> completableFuture = new CompletableFuture<>();
                    completableFuture.completeExceptionally(new AuthException(jsonNode.get("error").textValue()));
                    return completableFuture;
                } else {
                    return CompletableFuture.completedFuture(Json.fromJson(jsonNode, AuthInfo.class));
                }
            });
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AuthInfo {
            @JsonProperty("access_token")
            public String accessToken;

            @JsonProperty("instance_url")
            public String instanceUrl;
        }

        public static class AuthException extends Exception {
            AuthException(String message) {
                super(message);
            }
        }


    }

}
