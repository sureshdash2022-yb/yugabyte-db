package com.yugabyte.yw.controllers.handlers;

import static com.yugabyte.yw.commissioner.Common.CloudType.onprem;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.FORBIDDEN;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.yugabyte.yw.common.AccessManager;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ProviderEditRestrictionManager;
import com.yugabyte.yw.common.TemplateManager;
import com.yugabyte.yw.forms.AccessKeyFormData;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AccessKey.KeyInfo;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.ProviderDetails;
import com.yugabyte.yw.models.Region;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Http.RequestBody;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AccessKeyHandler {

  @Inject AccessManager accessManager;

  @Inject TemplateManager templateManager;

  @Inject ProviderEditRestrictionManager providerEditRestrictionManager;

  public AccessKey create(
      UUID customerUUID, Provider provider, AccessKeyFormData formData, RequestBody requestBody) {
    log.info(
        "Creating access key {} for customer {}, provider {}.",
        formData.keyCode,
        customerUUID,
        provider.uuid);
    return providerEditRestrictionManager.tryEditProvider(
        provider.uuid, () -> doCreate(provider, formData, requestBody));
  }

  private AccessKey doCreate(
      Provider provider, AccessKeyFormData formData, RequestBody requestBody) {
    try {
      List<Region> regionList = provider.regions;
      if (regionList.isEmpty()) {
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR, "Provider is in invalid state. No regions.");
      }
      Region region = regionList.get(0);

      if (formData.setUpChrony
          && region.provider.code.equals(onprem.name())
          && (formData.ntpServers == null || formData.ntpServers.isEmpty())) {
        throw new PlatformServiceException(
            BAD_REQUEST,
            "NTP servers not provided for on-premises"
                + " provider for which chrony setup is desired");
      }

      if (region.provider.code.equals(onprem.name()) && formData.sshUser == null) {
        throw new PlatformServiceException(
            BAD_REQUEST, "sshUser cannot be null for onprem providers.");
      }

      // Check if a public/private key was uploaded as part of the request
      MultipartFormData<File> multiPartBody = requestBody.asMultipartFormData();
      AccessKey accessKey;
      if (multiPartBody != null) {
        FilePart<File> filePart = multiPartBody.getFile("keyFile");
        File uploadedFile = filePart.getFile();
        if (formData.keyType == null || uploadedFile == null) {
          throw new PlatformServiceException(BAD_REQUEST, "keyType and keyFile params required.");
        }
        accessKey =
            accessManager.uploadKeyFile(
                region.uuid,
                uploadedFile,
                formData.keyCode,
                formData.keyType,
                formData.sshUser,
                formData.sshPort,
                formData.airGapInstall,
                formData.skipProvisioning,
                formData.setUpChrony,
                formData.ntpServers,
                formData.showSetUpChrony);
      } else if (formData.keyContent != null && !formData.keyContent.isEmpty()) {
        if (formData.keyType == null) {
          throw new PlatformServiceException(BAD_REQUEST, "keyType params required.");
        }
        // Create temp file and fill with content
        Path tempFile = Files.createTempFile(formData.keyCode, formData.keyType.getExtension());
        Files.write(tempFile, formData.keyContent.getBytes());

        // Upload temp file to create the access key and return success/failure
        accessKey =
            accessManager.uploadKeyFile(
                region.uuid,
                tempFile.toFile(),
                formData.keyCode,
                formData.keyType,
                formData.sshUser,
                formData.sshPort,
                formData.airGapInstall,
                formData.skipProvisioning,
                formData.setUpChrony,
                formData.ntpServers,
                formData.showSetUpChrony);
      } else {
        accessKey =
            accessManager.addKey(
                region.uuid,
                formData.keyCode,
                null,
                formData.sshUser,
                formData.sshPort,
                formData.airGapInstall,
                formData.skipProvisioning,
                formData.setUpChrony,
                formData.ntpServers,
                formData.showSetUpChrony);
      }

      // In case of onprem provider, we add a couple of additional attributes like
      // passwordlessSudo
      // and create a pre-provision script
      if (region.provider.code.equals(onprem.name())) {
        templateManager.createProvisionTemplate(
            accessKey,
            formData.airGapInstall,
            formData.passwordlessSudoAccess,
            formData.installNodeExporter,
            formData.nodeExporterPort,
            formData.nodeExporterUser,
            formData.setUpChrony,
            formData.ntpServers);
      }

      if (formData.expirationThresholdDays != null) {
        accessKey.updateExpirationDate(formData.expirationThresholdDays);
      }

      KeyInfo keyInfo = accessKey.getKeyInfo();
      keyInfo.mergeFrom(provider.details);
      return accessKey;
    } catch (IOException e) {
      log.error("Failed to create access key", e);
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR, "Failed to create access key: " + e.getLocalizedMessage());
    }
  }

  public AccessKey edit(UUID customerUUID, Provider provider, AccessKey accessKey, String keyCode) {
    log.info(
        "Editing access key {} for customer {}, provider {}.",
        keyCode,
        customerUUID,
        provider.uuid);
    return providerEditRestrictionManager.tryEditProvider(
        provider.uuid, () -> doEdit(provider, accessKey, keyCode));
  }

  private AccessKey doEdit(Provider provider, AccessKey accessKey, String keyCode) {
    long universesCount = provider.getUniverseCount();
    if (universesCount > 0) {
      throw new PlatformServiceException(
          FORBIDDEN, "Cannot modify the access key for the provider in use!");
    }

    ProviderDetails details = provider.details;
    AccessKey.KeyInfo keyInfo = accessKey.getKeyInfo();
    String keyPairName = keyInfo.keyPairName;
    if (keyPairName == null) {
      // If the keyPairName is not specified, generate a new keyCode
      // based on the provider.
      keyPairName = AccessKey.getNewKeyCode(provider);
    } else if (keyPairName.equals(keyCode)) {
      // If the passed keyPairName is same as the currentKeyCode, generate
      // the new Code appending timestamp to the current one.
      keyPairName = AccessKey.getNewKeyCode(keyPairName);
    }
    String sshPrivateKeyContent = keyInfo.getUnMaskedSshPrivateKeyContent();
    List<Region> regions = Region.getByProvider(provider.uuid);
    AccessKey newAccessKey = null;
    for (Region region : regions) {
      if (!Strings.isNullOrEmpty(sshPrivateKeyContent)) {
        newAccessKey =
            accessManager.saveAndAddKey(
                region.uuid,
                sshPrivateKeyContent,
                keyPairName,
                AccessManager.KeyType.PRIVATE,
                details.sshUser,
                details.sshPort,
                details.airGapInstall,
                false,
                details.setUpChrony,
                details.ntpServers,
                details.showSetUpChrony,
                false);
      } else {
        newAccessKey =
            accessManager.addKey(
                region.uuid,
                keyPairName,
                null,
                details.sshUser,
                details.sshPort,
                details.airGapInstall,
                false,
                details.setUpChrony,
                details.ntpServers,
                details.showSetUpChrony);
      }
    }
    return newAccessKey;
  }
}
