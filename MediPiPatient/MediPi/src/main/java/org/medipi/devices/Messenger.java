/*
 Copyright 2016  Richard Robinson @ HSCIC <rrobinson@hscic.gov.uk, rrobinson@nhs.net>

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.medipi.devices;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.medipi.DashboardTile;
import org.medipi.MediPi;
import org.medipi.MediPiMessageBox;
import org.medipi.downloadable.handlers.DownloadableHandlerManager;
import org.medipi.downloadable.handlers.MessageHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.medipi.authentication.UnlockConsumer;
import org.medipi.logging.MediPiLogger;
import org.medipi.model.AlertListDO;
import org.medipi.model.AlertDO;
import org.medipi.security.CertificateDefinitions;
import org.medipi.model.EncryptedAndSignedUploadDO;
import org.medipi.security.UploadEncryptionAdapter;
import org.medipi.utilities.Utilities;

/**
 * Class to display and handle the functionality for a incoming read-only
 * message display utility.
 *
 * This is a simple viewer of incoming messages from the clinician. It shows the
 * message title in a list of all messages received and displays the contents of
 * the selected message. As MediPi does not expose any inbound ports, incoming
 * messaging is achieved through periodic polling of a secure location. Any new
 * messages received are digested and the UI is updated. A new unread message
 * alerts the dashboard Tile class to superimpose an alert image. All messages
 * are persisted locally to a configurable file location.
 *
 * There is no view mode for this UI.
 *
 * @author rick@robinsonhq.com
 */
public class Messenger extends Element implements UnlockConsumer {

    private static final String NAME = "Clinician's Messages";
    private static final String MEDIPIIMAGESEXCLAIM = "medipi.images.exclaim";
    private TextArea messageView;
    private VBox messengerWindow;
    // not sure what the best setting for this thread pool is but 3 seems to work
    private static final int TIMER_THREAD_POOL_SIZE = 3;
    private String messageDir;
    private ImageView alertImageView;

    private final BooleanProperty alertBooleanProperty = new SimpleBooleanProperty(false);

    private TableView<Message> messageList;
    private ObservableList<Message> items = FXCollections.observableArrayList();
    private boolean locked = false;

    /**
     * Constructor for Messenger
     *
     */
    public Messenger() {

    }

    /**
     * Initiation method called for this Element.
     *
     * Successful initiation of the this class results in a null return. Any
     * other response indicates a failure with the returned content being a
     * reason for the failure
     *
     * @return populated or null for whether the initiation was successful
     * @throws java.lang.Exception
     */
    @Override
    public String init() throws Exception {

        String uniqueDeviceName = getClassTokenName();
        messengerWindow = new VBox();
        messengerWindow.setPadding(new Insets(0, 5, 0, 5));
        messengerWindow.setSpacing(5);
        messengerWindow.setMinSize(800, 350);
        messengerWindow.setMaxSize(800, 350);
        // get the image file for the alert image to be superimposed on the dashboard tile when a new message is received
        String alertImageFile = medipi.getProperties().getProperty(MEDIPIIMAGESEXCLAIM);
        alertImageView = new ImageView("file:///" + alertImageFile);

        // Create the view of the message content - scrollable
        messageView = new TextArea();
        messageView.setWrapText(true);
        messageView.isResizable();
        messageView.setEditable(false);
        messageView.setId("messenger-messagecontent");
        messageView.setMaxHeight(100);
        messageView.setMinHeight(100);
        ScrollPane viewSP = new ScrollPane();
        viewSP.setContent(messageView);
        viewSP.setFitToWidth(true);
        viewSP.setFitToHeight(true);
        viewSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        viewSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // location of the persistent message store
        messageDir = medipi.getProperties().getProperty(MediPi.ELEMENTNAMESPACESTEM + uniqueDeviceName + ".incomingmessagedirectory");
        if (messageDir == null || messageDir.trim().length() == 0) {
            throw new Exception("Message Directory parameter not configured");
        }
        Path dir = Paths.get(messageDir);
        messageList = new TableView<>();
        messageList.setId("messenger-messagelist");

        //Create the table of message
        // Message title list - scrollable and selectable 
        TableColumn messageTitleTC = new TableColumn("Message Title");
        messageTitleTC.setMinWidth(400);
        messageTitleTC.setCellValueFactory(
                new PropertyValueFactory<>("messageTitle"));
        TableColumn timeTC = new TableColumn("Time");
        timeTC.setMinWidth(100);
        timeTC.setCellValueFactory(
                new PropertyValueFactory<>("time"));

        File list[] = new File(dir.toString()).listFiles();

        // Comparitor to sort the messages by date in the message list - This is possible because 
        // the messages have a filename of format yyyyMMddHHmmss-messagename.txt 
        Arrays.sort(list, (File f1, File f2) -> Long.valueOf(f2.lastModified()).compareTo(f1.lastModified()));
        for (File f : list) {
            Message m;
            try {
                m = new Message(f.getName());
            } catch (Exception e) {
                continue;
            }

            items.add(m);
        }
        messageList.setMinHeight(140);
        messageList.setMaxHeight(140);
        messageList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        // Update the message text area when a new message is selected
        messageList.getSelectionModel().selectedItemProperty().addListener((ObservableValue<? extends Message> ov, Message old_val, Message new_val) -> {
            try {
                File file2 = new File(dir.toString(), new_val.getFileName());
                // alter to allow different read formats
                messageView.setText(readJSONAlert(file2));
            } catch (Exception ex) {
                if (ex.getLocalizedMessage() == null) {
                    messageView.setText("");
                } else {
                    messageView.setText("Cannot read message content" + ex.getLocalizedMessage());
                }
            }
        });
//        messageList.setItems(items);
        messageList.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        messageList.getColumns().addAll(messageTitleTC, timeTC);
        ScrollPane listSP = new ScrollPane();
        listSP.setContent(messageList);
        listSP.setFitToWidth(true);
        listSP.setPrefHeight(140);
        listSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        messageList.getSelectionModel().select(0);
        // Call the MessageWatcher class which will update the message list if 
        // a new txt file appears in the configured incoming message directory
        try {
            MessageWatcher mw = new MessageWatcher(dir, this);
        } catch (IOException ioe) {
            return "Message Watcher failed to initialise" + ioe.getMessage();
        }
        Label listLabel = new Label("Message List");
        listLabel.setId("messenger-list");
        Label textLabel = new Label("Message Text");
        textLabel.setId("messenger-text");

        messengerWindow.getChildren().addAll(
                listLabel,
                messageList,
                new Separator(Orientation.HORIZONTAL),
                textLabel,
                messageView,
                new Separator(Orientation.HORIZONTAL)
        );

        // set main Element window
        window.setCenter(messengerWindow);

        // if the dashboard tile has an alert superimposed remove it upon showing this element
        window.visibleProperty().addListener((ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) -> {
            if (newValue) {
                alertBooleanProperty.setValue(false);
            }
        });

        // Register this device with the handler
        DownloadableHandlerManager dhm = medipi.getDownloadableHandlerManager();
        dhm.addHandler("PATIENTMESSAGE", new MessageHandler(medipi.getProperties(), dir));
        medipi.getMediPiWindow().registerForAuthenticationCallback(this);
        // successful initiation of the this class results in a null return
        return null;
    }

    public void setItems(ObservableList<Message> items) {
        this.items = items;
        if (!locked) {
            messageList.setItems(items);
            messageList.getSelectionModel().select(0);
        }
    }

    /**
     * Method which returns a booleanProperty which UI elements can be bound to,
     * to discover whether a new message has arrived
     *
     * @return BooleanProperty signalling the presence of a new message
     */
    protected BooleanProperty getAlertBooleanProperty() {
        return alertBooleanProperty;
    }

    /**
     * getter method for the messageList so that the MessageWatcher can update
     * it
     *
     * @return
     */
    protected TableView<Message> getMessageList() {
        return messageList;
    }

    private String readJSONAlert(File file) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EncryptedAndSignedUploadDO encryptedAndSignedUploadDO = mapper.readValue(file, EncryptedAndSignedUploadDO.class);
        // instantiate the clinician encryption adapter
        UploadEncryptionAdapter clinicianEncryptionAdapter = new UploadEncryptionAdapter();

        CertificateDefinitions clinicianCD = new CertificateDefinitions(medipi.getProperties());
        clinicianCD.setSIGNTRUSTSTORELOCATION("medipi.json.sign.truststore.clinician.location", CertificateDefinitions.INTERNAL);
        clinicianCD.setSIGNTRUSTSTOREPASSWORD("medipi.json.sign.truststore.clinician.password", CertificateDefinitions.INTERNAL);
        clinicianCD.setENCRYPTKEYSTORELOCATION("medipi.patient.cert.location", CertificateDefinitions.INTERNAL);
        clinicianCD.setENCRYPTKEYSTOREALIAS("medipi.patient.cert.alias", CertificateDefinitions.INTERNAL);
        clinicianCD.setENCRYPTKEYSTOREPASSWORD("medipi.patient.cert.password", CertificateDefinitions.SYSTEM);

        String clinicianAdapterError;
        AlertListDO alertListDO = null;
        try {
            clinicianAdapterError = clinicianEncryptionAdapter.init(clinicianCD, UploadEncryptionAdapter.SERVERMODE);
            if (clinicianAdapterError != null) {
                MediPiLogger.getInstance().log(Messenger.class.getName() + ".error", "Failed to instantiate Clinician Encryption Adapter: " + clinicianAdapterError);
                throw new Exception("Failed to instantiate Clinician Encryption Adapter: " + clinicianAdapterError);
            }
            alertListDO = (AlertListDO) clinicianEncryptionAdapter.decryptAndVerify(encryptedAndSignedUploadDO);
        } catch (Exception e) {
            MediPiLogger.getInstance().log(Messenger.class.getName() + ".error", "Alert Decryption exception: " + e.getLocalizedMessage());
            throw new Exception("Alert Decryption exception: " + e.getLocalizedMessage());
        }
        try {

            StringBuilder sb = new StringBuilder();
            String type = "";
            Instant time = Instant.EPOCH;
            for (AlertDO alertDO : alertListDO.getAlert()) {
                if (!type.equals(alertDO.getType())) {
                    sb.append(alertDO.getType()).append(" Alert").append("\n");
                }
                if (!time.equals(alertDO.getAlertTime())) {
                    sb.append("Created on ").append(Utilities.DISPLAY_FORMAT_LOCALTIME.format(alertDO.getAlertTime().toInstant())).append("\n");
                }
                sb.append("\n").append(alertDO.getAlertText()).append("\n");
                type = alertDO.getType();
                time = alertDO.getAlertTime().toInstant();
            }
            return sb.toString();
        } catch (Exception e) {
            MediPiLogger.getInstance().log(Messenger.class.getName() + ".error", "Unable to save Alert exception: " + e.getLocalizedMessage());
            throw new Exception("Unable to save Alert exception: " + e.getLocalizedMessage());
        }

    }

    /**
     * getter for the Element Name
     *
     * @return String representation of the Element's Name
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * method to return the component to the dashboard
     *
     * @return @throws Exception
     */
    @Override
    public BorderPane getDashboardTile() throws Exception {
        DashboardTile dashComponent = new DashboardTile(this);
        dashComponent.addTitle(getName());
        dashComponent.addOverlay(alertImageView, alertBooleanProperty);
        return dashComponent.getTile();
    }

    /**
     * A method to allow callback failure of the messageWatcher
     *
     * @param failureMessage
     * @param e exception
     */
    protected void callFailure(String failureMessage, Exception e) {
        MediPiMessageBox.getInstance().makeErrorMessage(failureMessage, e);
    }

    @Override
    public void unlocked() {
        locked = false;
        messageList.setItems(items);
        messageList.getSelectionModel().select(0);
    }

    @Override
    public void locked() {
        locked = true;
        messageList.setItems(null);
    }

}
