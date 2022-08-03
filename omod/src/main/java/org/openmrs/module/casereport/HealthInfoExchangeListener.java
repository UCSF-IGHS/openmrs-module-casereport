/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.casereport;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.dcm4chee.xds2.common.XDSConstants;
import org.dcm4chee.xds2.infoset.ihe.ObjectFactory;
import org.dcm4chee.xds2.infoset.ihe.ProvideAndRegisterDocumentSetRequestType;
import org.dcm4chee.xds2.infoset.rim.RegistryError;
import org.dcm4chee.xds2.infoset.rim.RegistryResponseType;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.codesystems.ConditionClinical;
import org.hl7.fhir.r4.model.codesystems.ConditionVerStatus;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.Person;
import org.openmrs.api.APIException;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.CaseReport.Status;
import org.openmrs.module.casereport.api.CaseReportService;
import org.openmrs.module.casereport.api.CaseReportSubmittedEvent;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.*;
import org.openmrs.module.fhir2.api.search.param.SearchParameterMap;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.util.FhirUtils;
import org.openmrs.scheduler.TaskDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.xml.transform.StringResult;

import javax.xml.bind.JAXBElement;
import javax.xml.transform.Result;
import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;

/***
 * An instance of this class listens for event fired when a case report is
 * submitted so that it can generate and submit a CDA message to the HIE
 */
@Component
public class HealthInfoExchangeListener implements ApplicationListener<CaseReportSubmittedEvent> {

    static FhirContext CTX = FhirContext.forR4();
    protected final Log log = LogFactory.getLog(getClass());
    private final ObjectFactory objectFactory = new ObjectFactory();
    private final SimpleDateFormat sqlDateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat localeDateformatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
    @Autowired
    FhirEncounterService fhirEncounterService;

    @Autowired
    EncounterService encounterService;
    @Autowired
    ObsService obsService;

    @Autowired
    FhirPractitionerService fhirPractitionerService;

    @Autowired
    FhirLocationService fhirLocationService;

    @Autowired
    FhirObservationService fhirObservationService;
    @Autowired
    private FhirPatientService fhirPatientService;
    @Autowired
    private WebServiceTemplate webServiceTemplate;
    @Autowired
    private WebServiceMessageCallback messageCallback;
    @Autowired
    private ConceptTranslator conceptTranslator;

    /**
     * @see ApplicationListener#onApplicationEvent(ApplicationEvent)
     */
    @Override
    public void onApplicationEvent(CaseReportSubmittedEvent event) {
        localeDateformatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        //Get the format
        String format = DocumentUtil.getCaseReportFormat();
        CaseReport caseReport = (CaseReport) event.getSource();
        String report = null;
        String response = null;
        JAXBElement rootElement = null;

        try {
            if (format.equals("FHIR")) {
                report = createFHIRMessage(caseReport);
                response = postFhirMessage(report);
            } else {
                CaseReportForm form = new ObjectMapper().readValue(caseReport.getReportForm(), CaseReportForm.class);
                form.setReportUuid(caseReport.getUuid());
                form.setReportDate(caseReport.getDateCreated());
                ProvideAndRegisterDocumentSetRequestType docRequest = new ProvideAndRegisterDocGenerator(form).generate();
                rootElement = objectFactory.createProvideAndRegisterDocumentSetRequest(docRequest);

                Result out = new StringResult();
                webServiceTemplate.getMarshaller().marshal(rootElement, out);

                if (log.isDebugEnabled()) {
                    log.debug("Saving Case report document to the file system.....");
                }
                response = postCDAMessage(rootElement);
            }

            //for demo, use the event date as resolution date instead of current datetime
            if (Context.getAdministrationService().getGlobalProperty(CaseReportWebConstants.ORG_NAME).toLowerCase()
                    .contains("demo")) {
                CaseReportForm form = new ObjectMapper().readValue(caseReport.getReportForm(), CaseReportForm.class);
                DatedUuidAndValue triggerValue = form.getTriggers().get(0);
                Date resolutionDate = updateTriggerDate(triggerValue.getValue(), caseReport.getPatient().getId(), form);
                caseReport.setResolutionDate(resolutionDate);
            }

            if (response != null && response.indexOf("ERROR") == -1) {
                File docFile = DocumentUtil.getSubmittedCaseReportFile(caseReport);

                if (format.contentEquals("FHIR")) {
                    FileUtils.writeStringToFile(docFile, report, DocumentConstants.ENCODING);
                } else {
                    Result out = new StringResult();
                    webServiceTemplate.getMarshaller().marshal(rootElement, out);
                    FileUtils.writeStringToFile(docFile, out.toString(), DocumentConstants.ENCODING);
                }
                setCaseReportStatus(caseReport, true);
            } else {
                setCaseReportStatus(caseReport, false);
            }

            if (log.isDebugEnabled()) {
                log.debug("Case report document successfully saved to the file system");
            }

            if (log.isDebugEnabled()) {
                log.debug("Sending Case report document.....");
            }

        } catch (Exception e) {
            log.warn("An error occurred while submitting a case report document to the HIE \n" + e);
            setCaseReportStatus(caseReport, false);
            APIException rethrow;
            if (e instanceof APIException) {
                rethrow = (APIException) e;
            } else {
                rethrow = new APIException(e);
            }

            throw rethrow;
        }

    }

    private String createFHIRMessage(CaseReport caseReport) {

        try {
            // patient data
            String personUUID = caseReport.getPatient().getPerson().getUuid();
            org.hl7.fhir.r4.model.Patient patient = fhirPatientService.get(personUUID);
            setOfficialName(patient.getName());
            setOpenhieId(patient);
            patient.getIdentifier().add(setOrganizationId());

            // encounter data
            ReferenceAndListParam subjectReference = new ReferenceAndListParam();
            ReferenceParam subject = new ReferenceParam();
            subject.setValue(patient.getId());
            subjectReference.addValue(new ReferenceOrListParam().add(subject));
            IBundleProvider encounterRecords = fhirEncounterService.searchForEncounters(null,
                    null, null, subjectReference, null,
                    null, null, null, null);

            // obs data
            SearchParameterMap theParams = new SearchParameterMap();
            theParams.addParameter(FhirConstants.PATIENT_REFERENCE_SEARCH_HANDLER, subjectReference);

            // TODO pass in the correct search params
            CaseReportTrigger caseReportTrigger = caseReport.getCaseReportTriggerByName("New HIV Case");
            CaseReportTrigger vlCaseReportTrigger = caseReport.getCaseReportTriggerByName("Viral Load Request");
            IBundleProvider observationRecords;
            if (caseReportTrigger != null) {
                TaskDefinition taskDefinition = Context.getSchedulerService().getTaskByName(caseReportTrigger.getName());
                String obsConcept = taskDefinition.getProperty(CaseReportConstants.OBS_CONCEPT);
                Concept c = CaseReportUtil.getConceptByMappingString(obsConcept, false);
                TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
                TokenParam conceptTokenParam = new TokenParam(c.getUuid());
                conceptTokenAndListParam.addAnd(conceptTokenParam);

                String obsValueCoded = taskDefinition.getProperty(CaseReportConstants.OBS_VALUE_CODED);
                Concept v = CaseReportUtil.getConceptByMappingString(obsValueCoded, false);
                TokenAndListParam vcodedTokenAndListParam = new TokenAndListParam();
                TokenParam vcodedTokenParam = new TokenParam(v.getUuid());
                vcodedTokenAndListParam.addAnd(vcodedTokenParam);
                observationRecords = fhirObservationService.searchForObservations(null,
                        subjectReference, null, vcodedTokenAndListParam, null,
                        null, null, null, conceptTokenAndListParam,
                        null, null, null, null, null, null);
            } else if (vlCaseReportTrigger != null) {
                TaskDefinition taskDefinition = Context.getSchedulerService().getTaskByName(vlCaseReportTrigger.getName());
                String obsConcept = taskDefinition.getProperty(CaseReportConstants.OBS_CONCEPT);
                Concept concept = CaseReportUtil.getConceptByMappingString(obsConcept, false);
                TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
                conceptTokenAndListParam.addAnd(new TokenParam(concept.getUuid()));

                observationRecords = fhirObservationService.getLastnEncountersObservations(new NumberParam(1),
                        subjectReference, null, conceptTokenAndListParam);
            } else {
                observationRecords = fhirObservationService.searchForObservations(null,
                        subjectReference, null, null, null,
                        null, null, null, null,
                        null, null, null, null, null, null);
            }

            Bundle bundle = new Bundle();
            bundle.setId(UUID.randomUUID().toString());
            bundle.setType(Bundle.BundleType.TRANSACTION);

            Composition composition = new Composition();
            composition.setId(UUID.randomUUID().toString());

            String patientFullUrl = "Patient/" + patient.getIdElement().getValue();
            bundle.addEntry().setFullUrl(patientFullUrl).setResource(patient).getRequest()
                    .setUrl("Patient").setMethod(Bundle.HTTPVerb.POST);

            Composition.SectionComponent sectionComponent = new Composition.SectionComponent();
            sectionComponent.setTitle("Patient Info");
            sectionComponent.addEntry(new Reference(patientFullUrl));
            composition.addSection(sectionComponent);

            if (observationRecords.size() > 0) {
                // Add Observations
                sectionComponent = new Composition.SectionComponent();
                sectionComponent.setTitle("Observation Info");
                String observationFullUrl;
                for (int index = 0; index < observationRecords.size(); index++) {
                    Observation observation = (Observation) observationRecords.getResources(index, index).get(0);

                    if (observation.hasEncounter()) {
                        String encounterUuid = FhirUtils.referenceToId(observation.getEncounter().getReference()).orElse("");
                        Encounter encounter = fhirEncounterService.get(encounterUuid);
                        if (encounter != null) {
                            sectionComponent.setTitle("Encounter Info");
                            org.openmrs.module.casereport.FhirUtils.processEncounter(bundle, sectionComponent,
                                    encounter, fhirEncounterService, fhirPractitionerService, fhirLocationService);
                            if (vlCaseReportTrigger != null) {
                                processVLRequest(bundle, encounter, observation,
                                        patientFullUrl, caseReport.getPatient().getPerson());
                            }
                        }
                    }

                    if (caseReportTrigger != null) {
                        processNewHivCondition(bundle, sectionComponent, caseReportTrigger, observation);
                    }

                    observationFullUrl = "Observation/" + observation.getIdElement().getValue();
                    bundle.addEntry().setFullUrl(observationFullUrl).setResource(observation).getRequest()
                            .setUrl("Observation").setMethod(Bundle.HTTPVerb.POST);
                    sectionComponent.addEntry(new Reference(observationFullUrl));
                }
                composition.addSection(sectionComponent);
            }

            Bundle.BundleEntryComponent compositionBundleEntryComponent = new Bundle.BundleEntryComponent();
            compositionBundleEntryComponent.setFullUrl("Composition/" + composition.getIdElement().getValue())
                    .setResource(composition).getRequest().setUrl("Composition").setMethod(Bundle.HTTPVerb.POST);
            bundle.getEntry().add(0, compositionBundleEntryComponent);

            String jsonToSend = CTX.newJsonParser().encodeResourceToString(bundle);
            log.info(jsonToSend);

            return jsonToSend;

        } catch (Exception e) {
            log.error("Exception -- " + e.getMessage());
        }
        return null;
    }

    private void processVLRequest(Bundle bundle, Encounter encounter, Observation dateTestOrderedObs,
                                  String patientFullUrl, Person person) {

        String specimenTypeConceptUUID = "9103ae1f-1461-4fcb-91d7-3705a40f5f5c";
        String reasonForVLConceptUUID = "86cc0cfe-bace-4969-94b6-d139f4971d13";
        String specimenIdConceptUUID = "159968AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        String uuid = encounter.getIdElement().getValue();
        org.openmrs.Encounter openMrsEncounter = encounterService.getEncounterByUuid(uuid);
        List<Obs> observations = obsService.getObservations(Arrays.asList(person), Arrays.asList(openMrsEncounter), null, null,
                null, null, null, null, null, null, null, false, null);

        Obs specimenTypeObs = observations.stream().filter(obs ->
                obs.getConcept().getUuid().equals(specimenTypeConceptUUID)).findFirst().get();

        Obs reasonForVLObs = observations.stream().filter(obs ->
                obs.getConcept().getUuid().equals(reasonForVLConceptUUID)).findFirst().get();

        Obs specimenIdObs = observations.stream().filter(obs ->
                obs.getConcept().getUuid().equals(specimenIdConceptUUID)).findFirst().get();

        Date today = new Date();
        String organizationReference = "Organization/101282";
        String labReference = "Organization/101283";

        String specimenUUID = UUID.randomUUID().toString();
        String specimenFullUrl = "Specimen/" + specimenUUID;
        Specimen specimen = new Specimen();
        specimen.setId(specimenUUID);
        CodeableConcept specimenType = new CodeableConcept();
        specimenType.addCoding()
                .setCode(specimenTypeObs.getValueCoded().getName().getName())
                .setSystem("http://openhie.org/fhir/lab-integration/specimen-type-code");
        specimen.setType(specimenType);
        Specimen.SpecimenCollectionComponent specimenCollectionInfo = new Specimen.SpecimenCollectionComponent();
        specimenCollectionInfo.addChild("collectedDateTime");
        specimenCollectionInfo.setCollected(new DateTimeType(today));
        specimen.setCollection(specimenCollectionInfo);
        addBundleEntry(bundle, specimenFullUrl, specimen, "Specimen");

        String serviceRequestUUID = UUID.randomUUID().toString();
        String serviceRequestFullUrl = "ServiceRequest/" + serviceRequestUUID;
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setId(serviceRequestUUID);
        serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        serviceRequest.setSubject(new Reference(patientFullUrl));
        serviceRequest.setSpecimen(Arrays.asList(new Reference(specimenFullUrl)));
        serviceRequest.setAuthoredOn(dateTestOrderedObs.getValueDateTimeType().getValue());

        serviceRequest
                .addReasonCode()
                .addCoding()
                .setSystem("http://openhie.org/fhir/lab-integration/vl-reason-code")
                .setCode(reasonForVLObs.getValueCoded().getName().getName());

        addBundleEntry(bundle, serviceRequestFullUrl, serviceRequest, "ServiceRequest");

        String taskFullUrl = "Task/" + uuid;
        Task task = new Task();
        task.setStatus(Task.TaskStatus.REQUESTED);
        task.setId(uuid);
        task.setIntent(Task.TaskIntent.ORDER);
        task.addIdentifier().setSystem("OHRI_ENCOUNTER_UUID").setValue(uuid);
        task.addIdentifier().setSystem("http://openhie.org/fhir/lab-integration/test-order-number")
                .setValue(String.valueOf(specimenIdObs.getValueNumeric().intValue()));
        task.setRequester(new Reference(organizationReference));
        task.setOwner(new Reference(labReference));
        task.setLastModified(today);
        task.addBasedOn(new Reference(serviceRequestFullUrl));
        addBundleEntry(bundle, taskFullUrl, task, "Task");
    }

    private void addBundleEntry(Bundle bundle, String resourceFullUrl, Resource resource, String url) {
        bundle.addEntry()
                .setFullUrl(resourceFullUrl)
                .setResource(resource)
                .getRequest()
                .setUrl(url)
                .setMethod(Bundle.HTTPVerb.POST);
    }

    private void processNewHivCondition(Bundle bundle, Composition.SectionComponent sectionComponent,
                                        CaseReportTrigger caseReportTrigger, Observation observation) {
        Condition condition = new Condition();
        condition.setId(observation.getIdElement().getValue());
        condition.setSubject(observation.getSubject());
        if (observation.hasEncounter()) {
            condition.setEncounter(observation.getEncounter());
        }
        condition.setClinicalStatus(new CodeableConcept(new Coding().setCode(ConditionClinical.ACTIVE.toCode())));
        condition.setVerificationStatus(new CodeableConcept(new Coding().setCode(ConditionVerStatus.CONFIRMED.toCode())));
        condition.setRecordedDate(observation.getIssued());

        TaskDefinition taskDefinition = Context.getSchedulerService().getTaskByName(caseReportTrigger.getName());
        String conceptMap = taskDefinition.getProperty(CaseReportConstants.CONCEPT_TASK_PROPERTY);
        if (!StringUtils.isBlank(conceptMap)) {
            Concept concept = CaseReportUtil.getConceptByMappingString(conceptMap, false);
            if (concept != null) {
                condition.setCode(conceptTranslator.toFhirResource(concept));
            }
        } else {
            condition.setCode(observation.getValueCodeableConcept());
        }

        String conditionFullUrl = "Condition/" + condition.getIdElement().getValue();
        bundle.addEntry().setFullUrl(conditionFullUrl).setResource(condition).getRequest().setUrl("Condition")
                .setMethod(Bundle.HTTPVerb.POST);
        sectionComponent.addEntry(new Reference(conditionFullUrl));
    }

    private void setOfficialName(List<HumanName> humanNames) {
        for (HumanName humanName : humanNames) {
            if (humanName.hasUse() && humanName.getUse().equals(HumanName.NameUse.OFFICIAL)) {
                return;
            }
        }
        if (humanNames.get(0) != null) {
            humanNames.get(0).setUse(HumanName.NameUse.OFFICIAL);
        }
    }

    private void setOpenhieId(Patient patient) {
        Identifier identifier = new Identifier();
        identifier.setSystem("https://instantopenhie.org/client3");
        identifier.setValue(patient.getIdElement().getValue());
        patient.getIdentifier().add(identifier);
    }

    private Identifier setOrganizationId() {
        Identifier identifier = new Identifier();
        identifier.setSystem("urn:oid:" + DocumentUtil.getOrganizationOID());
        identifier.setUse(Identifier.IdentifierUse.OFFICIAL);
        return identifier;
    }

    private Date updateTriggerDate(Object value, Integer patientId, CaseReportForm form) throws Exception {
        String triggerName = (String) value;

        String date = null;
        if (triggerName.equals("HIV First CD4 Count")) {
            date = sqlDateFormatter.format(localeDateformatter.parse(form.getMostRecentCd4Count().getDate()));
        } else if (triggerName.equals("New HIV Case")) {
            date = sqlDateFormatter.format(localeDateformatter.parse(form.getMostRecentHivTest().getDate()));
        } else if (triggerName.equals("New HIV Treatment")) {
            Person person = new Person(patientId);
            Concept concept = new Concept(1255);
            List<Obs> obs = Context.getObsService().getObservationsByPersonAndConcept(person, concept);
            if (obs.size() > 0) {
                date = sqlDateFormatter.format(obs.get(0).getObsDatetime());
            }
        } else if (triggerName.equals("HIV Treatment Failure")) {
            date = sqlDateFormatter.format(localeDateformatter.parse(form.getMostRecentViralLoad().getDate()));
        } else if (triggerName.equals("HIV Patient Died")) {
            date = sqlDateFormatter.format(localeDateformatter.parse(form.getDeathdate()));
        } else {
            date = sqlDateFormatter.format(new Date());
        }
        if (date != null) {
            return sqlDateFormatter.parse(date);
        }
        return new Date();
    }

    private void setCaseReportStatus(CaseReport caseReport, boolean isSuccess) {
        CaseReportService crs = Context.getService(CaseReportService.class);
        if (isSuccess) {
            caseReport.setStatus(Status.SUBMITTED);
        } else {
            caseReport.setStatus(Status.NEW);
        }
        if (caseReport.getResolutionDate() == null) {
            caseReport.setResolutionDate(new Date());
        }
        crs.saveCaseReport(caseReport);
    }

    public String postCDAMessage(JAXBElement rootElement) throws Exception {
        String url = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_URL);
        Object response = webServiceTemplate.marshalSendAndReceive(url, rootElement, messageCallback);
        String lf = SystemUtils.LINE_SEPARATOR;
        RegistryResponseType regResp = ((JAXBElement<RegistryResponseType>) response).getValue();
        if (!XDSConstants.XDS_B_STATUS_SUCCESS.equals(regResp.getStatus())) {
            StringBuffer sb = new StringBuffer();
            if (regResp.getRegistryErrorList() != null && regResp.getRegistryErrorList().getRegistryError() != null) {
                for (RegistryError re : regResp.getRegistryErrorList().getRegistryError()) {
                    sb.append("Severity: "
                            + (StringUtils.isNotBlank(re.getSeverity()) ? re.getSeverity().substring(
                            re.getSeverity().lastIndexOf(":") + 1) : "?") + ", Code: "
                            + (StringUtils.isNotBlank(re.getErrorCode()) ? re.getErrorCode() : "?") + ", Message: "
                            + (StringUtils.isNotBlank(re.getCodeContext()) ? re.getCodeContext() : "?") + lf);
                }
            }

            throw new APIException(sb.toString());
        }
        return regResp.toString();
    }

    public String postFhirMessage(String report) throws Exception {
        String url = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_URL);
        String username = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_CLIENT_ID);
        String password = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_CLIENT_PASSWORD);
        URI uri = new URI(url);

        // create auth credentials
        String auth = username + ":" + password;
        String base64Creds = Base64.getEncoder().encodeToString(auth.getBytes());

        // create headers
        HttpHeaders headers = new HttpHeaders();
        //headers.add("Authorization", "Basic " + base64Creds);
        //headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", "Custom test");
        headers.add("Content-Type", "application/fhir+json");

        // create request
        HttpEntity<String> request = new HttpEntity<>(report, headers);
        // make a request
        ResponseEntity<String> response = new RestTemplate().postForEntity(uri, request, String.class);
        // get response
        return response.getBody();

    }

}
