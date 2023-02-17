package org.openmrs.module.casereport.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.AdditionalRequestHeadersInterceptor;
import org.apache.commons.lang.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.DocumentConstants;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class FhirClient {

	static FhirContext CTX = FhirContext.forR4();

	public static IGenericClient getClient() throws URISyntaxException {
		String url = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_URL);
		String username = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_CLIENT_ID);
		String password = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_CLIENT_PASSWORD);
		URI uri = new URI(url);
		System.out.println("URI: " + uri);

		// create auth credentials
		String auth = username + ":" + password;
		String base64Creds = Base64.getEncoder().encodeToString(auth.getBytes());

		IGenericClient client = CTX.newRestfulGenericClient(uri.toString());
		AdditionalRequestHeadersInterceptor interceptor = new AdditionalRequestHeadersInterceptor();
		//interceptor.addHeaderValue("Authorization", "Basic " + base64Creds);
		interceptor.addHeaderValue("Authorization", "Custom test");
		interceptor.addHeaderValue("Content-Type", "application/fhir+json");
		client.registerInterceptor(interceptor);

		return client;
	}

	public static String postFhirMessage(String report) throws Exception {
		String url = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_URL);
		String username = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_CLIENT_ID);
		String password = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_CLIENT_PASSWORD);
		URI uri = new URI(url);
		System.out.println("URI: " + uri);

		// create headers
		HttpHeaders headers = new HttpHeaders();

		if (StringUtils.isNotEmpty(username) && StringUtils.isNotEmpty(password)) {
			// create auth credentials
			String auth = username + ":" + password;
			String base64Creds = Base64.getEncoder().encodeToString(auth.getBytes());
			headers.add("Authorization", "Basic " + base64Creds);
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.add("Content-Type", "application/fhir+json");
		} else {
			headers.add("Authorization", "Custom test");
			headers.add("Content-Type", "application/fhir+json");
		}

		// create request
		HttpEntity<String> request = new HttpEntity<>(report, headers);
		// make a request
		ResponseEntity<String> response = new RestTemplate().postForEntity(uri, request, String.class);
		// get response
		String resp = response.getBody();
		System.out.println("\nResponse: \n" + resp + "\n");
		return resp;

	}

}
