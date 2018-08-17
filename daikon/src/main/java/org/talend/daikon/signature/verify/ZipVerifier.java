// ============================================================================
//
// Copyright (C) 2006-2018 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.daikon.signature.verify;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSigner;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.talend.daikon.signature.exceptions.InvalidKeyStoreException;
import org.talend.daikon.signature.exceptions.MissingEntryException;
import org.talend.daikon.signature.exceptions.NoCodeSignCertificateException;
import org.talend.daikon.signature.exceptions.NoValidCertificateException;
import org.talend.daikon.signature.exceptions.UnsignedEntryException;
import org.talend.daikon.signature.exceptions.VerifyException;
import org.talend.daikon.signature.exceptions.VerifyFailedException;
import org.talend.daikon.signature.keystore.KeyStoreManager;

public class ZipVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZipVerifier.class);

    private PKIXParameters param;

    public ZipVerifier(InputStream keyStoreInputStream, String keyStorePass)
            throws InvalidKeyStoreException, KeyStoreException, InvalidAlgorithmParameterException {
        assert (keyStoreInputStream != null && keyStorePass != null);
        initPKIXParameter(keyStoreInputStream, keyStorePass);
    }

    private void initPKIXParameter(InputStream keyStoreInputStream, String keyStorePass)
            throws InvalidKeyStoreException, KeyStoreException, InvalidAlgorithmParameterException {
        KeyStoreManager keyStoreManager = new KeyStoreManager();
        keyStoreManager.loadKeyStore(keyStoreInputStream, keyStorePass);
        final KeyStore keyStore = keyStoreManager.getVerifyKeyStore();
        if (keyStore == null) {
            throw new InvalidKeyStoreException("Can't load the key store"); //$NON-NLS-1$
        }
        param = new PKIXParameters(keyStore);
        param.setRevocationEnabled(false);
    }

    public void verify(String filePath) throws Exception {
        assert (filePath != null);
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("The file does not exist:" + filePath); //$NON-NLS-1$
        }
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(filePath);
            Manifest mainfest = jarFile.getManifest();
            if (mainfest == null) {
                throw new MissingEntryException("Missing entry:" + JarFile.MANIFEST_NAME); //$NON-NLS-1$
            }
            Map<String, Attributes> manifestEntryMap = mainfest.getEntries();
            Enumeration<JarEntry> entriesEnum = jarFile.entries();
            Set<String> verifiedEntryNameSet = new HashSet<String>();
            while (entriesEnum.hasMoreElements()) {
                JarEntry entry = entriesEnum.nextElement();
                if (entry.isDirectory() || isSignatureRelatedEntry(entry.getName())) {
                    continue;
                }
                readAndCheckEntry(jarFile, entry);
                if (!manifestEntryMap.containsKey(entry.getName()) || entry.getCodeSigners() == null
                        || entry.getCodeSigners().length == 0) {
                    throw new UnsignedEntryException("Find unsigned entry:" + entry.getName());
                }
                checkCodeSigners(entry);
                verifiedEntryNameSet.add(entry.getName());
            }

            // Check signed the entry number
            if (manifestEntryMap.size() != verifiedEntryNameSet.size()) {
                for (String key : manifestEntryMap.keySet()) {
                    if (!verifiedEntryNameSet.contains(key)) {
                        throw new MissingEntryException("Missing entry:" + key); //$NON-NLS-1$
                    }
                }
            }
        } catch (Exception ex) {
            throw new VerifyFailedException("Verify failed." + ex.getMessage(), ex); //$NON-NLS-1$
        } finally {
            if (jarFile != null) {
                jarFile.close();
            }
        }
    }

    private void readAndCheckEntry(JarFile jarFile, JarEntry entry) throws VerifyFailedException {
        InputStream is = null;
        byte[] buffer = new byte[8192];
        try {
            is = jarFile.getInputStream(entry);
            while ((is.read(buffer, 0, buffer.length)) != -1)
                ;
        } catch (java.lang.SecurityException ex) {
            throw new VerifyFailedException("Verify failed." + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new VerifyFailedException("Verify failed." + ex.getMessage(), ex);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ex) {
                    LOGGER.debug("Close stream failed:" + ex);
                }
            }
        }
    }

    private void checkCodeSigners(JarEntry entry) throws NoSuchAlgorithmException, CertPathValidatorException,
            InvalidAlgorithmParameterException, CertificateException, VerifyException {
        boolean isContainSignCert = false;
        for (CodeSigner cs : entry.getCodeSigners()) {
            if (!isContainSignCert && isContainCodeSignCert(cs)) {
                isContainSignCert = true;
            }
            if (cs.getTimestamp() != null) {
                param.setDate(cs.getTimestamp().getTimestamp());
            } else {
                param.setDate(null);
            }
            PKIXCertPathValidatorResult result = validate(cs.getSignerCertPath());
            if (result == null) {
                throw new VerifyException("No validate result for cert path."); //$NON-NLS-1$
            }
        }
        if (!isContainSignCert) {
            throw new NoCodeSignCertificateException("Can't find any code sign certificate for the entry:" + entry.getName()); //$NON-NLS-1$
        }
    }

    private PKIXCertPathValidatorResult validate(CertPath certPath) throws NoSuchAlgorithmException, CertPathValidatorException,
            InvalidAlgorithmParameterException, NoValidCertificateException, CertificateException {
        if (certPath == null || certPath.getCertificates() == null || certPath.getCertificates().size() == 0) {
            throw new NoValidCertificateException("No valid certificate"); //$NON-NLS-1$
        }
        List<? extends Certificate> certList = certPath.getCertificates();
        List<X509Certificate> validCertList = new ArrayList<X509Certificate>();
        for (Certificate cert : certList) {
            if (cert instanceof X509Certificate) {
                X509Certificate x509Cert = (X509Certificate) cert;
                try {
                    if (param.getDate() == null) {
                        x509Cert.checkValidity();
                    } else {
                        x509Cert.checkValidity(param.getDate());
                    }
                    validCertList.add(x509Cert);
                } catch (CertificateExpiredException | CertificateNotYetValidException ex) {
                    LOGGER.debug("Find invalid certificate:" + ex);
                }
            }
        }
        if (validCertList.size() == 0) {
            throw new NoValidCertificateException("No valid certificate, all certificates are expired."); //$NON-NLS-1$
        }
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509"); //$NON-NLS-1$
        CertPath toVerifyCertPath = certificateFactory.generateCertPath(validCertList);
        CertPathValidator validator = CertPathValidator.getInstance("PKIX"); //$NON-NLS-1$
        return (PKIXCertPathValidatorResult) validator.validate(toVerifyCertPath, param);
    }

    private boolean isSignatureRelatedEntry(String entryName) {
        return entryName.equals(JarFile.MANIFEST_NAME) || entryName.matches("META-INF/.*.SF") //$NON-NLS-1$
                || entryName.matches("META-INF/.*.RSA");
    }

    private boolean isContainCodeSignCert(CodeSigner codeSigner) throws CertificateParsingException {
        if (codeSigner != null) {
            List<? extends Certificate> certificateList = codeSigner.getSignerCertPath().getCertificates();
            if (certificateList != null) {
                for (Object cert : certificateList) {
                    if (cert instanceof X509Certificate && isCodeSignCert((X509Certificate) cert)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isCodeSignCert(final X509Certificate cert) throws CertificateParsingException {
        List<String> keyUsage = cert.getExtendedKeyUsage();
        return keyUsage != null && (keyUsage.contains("2.5.29.37.0") || keyUsage.contains("1.3.6.1.5.5.7.3.3")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}