package com.scooterrentalkandy.spring.services;

import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Plain-text email through Amazon SES (SDS 4.1, 7.1) with the official AWS SDK v2. Credentials come from the
 * default AWS credential provider chain (environment, shared profile, or the instance/container role); the
 * application never reads or stores them. Errors never carry the recipient address or the message.
 */
public class SesEmailSender implements EmailSender {

    private static final String UTF_8 = "UTF-8";

    private final SesV2Client client;
    private final String from;

    public SesEmailSender(SesV2Client client, String from) {
        this.client = client;
        this.from = from;
    }

    /** The production client: the configured region, the default credential chain and bounded call time. */
    public static SesV2Client client(String region) {
        return SesV2Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .build();
    }

    @Override
    public void send(String to, String subject, String body) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(d -> d.toAddresses(to))
                .content(c -> c.simple(m -> m
                        .subject(text(singleLine(subject)))
                        .body(b -> b.text(text(body)))))
                .build();
        try {
            client.sendEmail(request);
        } catch (SesV2Exception ex) {
            String code = ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode();
            throw new EmailDeliveryException("SES refused the message: " + (code == null ? "unknown error" : code)
                    + " (HTTP " + ex.statusCode() + ")", ex);
        } catch (SdkException ex) {
            throw new EmailDeliveryException("SES could not be reached: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private static Content text(String data) {
        return Content.builder().charset(UTF_8).data(data).build();
    }

    /** A subject is one header line. */
    private static String singleLine(String subject) {
        return subject.replaceAll("[\\r\\n]+", " ").strip();
    }
}
