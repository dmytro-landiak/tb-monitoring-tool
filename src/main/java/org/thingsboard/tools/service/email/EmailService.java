/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

@Service
@Slf4j
public class EmailService {

    @Value("${rest.url}")
    private String restUrl;

    @Value("${email.tbStatusEmails}")
    private String tbStatusEmails;

    @Value("${email.scriptStatusEmails}")
    private String scriptStatusEmails;

    @Value("${email.smtp.starttls.enable}")
    private Boolean smtpStartTlsEnable;

    @Value("${email.smtp.auth}")
    private Boolean smtpAuth;

    @Value("${email.smtp.host}")
    private String smtpHost;

    @Value("${email.smtp.port}")
    private Integer smtpPort;

    @Value("${email.smtp.username}")
    private String smtpUsername;

    @Value("${email.smtp.password}")
    private String smtpPassword;

    private Session session;

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put("mail.smtp.starttls.enable", smtpStartTlsEnable);
        props.put("mail.smtp.auth", smtpAuth);
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort);

        session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUsername, smtpPassword);
            }
        });
    }

    public void sendAlertEmail() {
        try {
            Transport.send(createMessage(tbStatusEmails, "ThingsBoard Status [" + restUrl + "]",
                    "ThingsBoard is currently down or in bad conditions!"));
        } catch (MessagingException e) {
            log.warn("Failed to send the mail about TB conditions!", e);
            throw new RuntimeException(e);
        }
    }

    public void sendStatusEmail() {
        try {
            Transport.send(createMessage(scriptStatusEmails, "ThingsBoard Script Status [" + restUrl + "]",
                    "Script is working well!"));
        } catch (MessagingException e) {
            log.warn("Failed to send the mail about script status!", e);
            throw new RuntimeException(e);
        }
    }

    public void sendRestoringEmail() {
        try {
            Transport.send(createMessage(tbStatusEmails, "ThingsBoard Status [" + restUrl + "]",
                    "ThingsBoard has restored its work!"));
        } catch (MessagingException e) {
            log.warn("Failed to send the mail about TB conditions!", e);
            throw new RuntimeException(e);
        }
    }

    private Message createMessage(String emailAddresses, String subject, String text) throws MessagingException {
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpUsername));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailAddresses));
        message.setSubject(subject);
        message.setText(text);
        return message;
    }

}
