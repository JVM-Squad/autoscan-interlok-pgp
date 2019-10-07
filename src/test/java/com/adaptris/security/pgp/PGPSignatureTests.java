package com.adaptris.security.pgp;

import com.adaptris.core.AdaptrisMessage;
import com.adaptris.core.AdaptrisMessageFactory;
import com.adaptris.core.MetadataElement;
import com.adaptris.core.common.ConstantDataInputParameter;
import com.adaptris.core.common.MetadataDataInputParameter;
import com.adaptris.core.common.MetadataStreamInputParameter;
import com.adaptris.core.common.MetadataStreamOutputParameter;
import com.adaptris.core.common.PayloadStreamInputParameter;
import com.adaptris.core.common.PayloadStreamOutputParameter;
import com.adaptris.core.common.StringPayloadDataInputParameter;
import com.adaptris.core.common.StringPayloadDataOutputParameter;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class PGPSignatureTests extends PGPTests
{
	@Test
	public void testSignatureDetached() throws Exception
	{
		AdaptrisMessage message = AdaptrisMessageFactory.getDefaultInstance().newMessage(MESSAGE);
		PGPSignService sign = getSignService(privateKey, PASSPHRASE, true, true);

		sign.doService(message);

		message = AdaptrisMessageFactory.getDefaultInstance().newMessage(message.getContent());
		message.addMetadata("message", MESSAGE);
		PGPVerifyService verify = getVerifyService(publicKey, true);

		verify.doService(message);

		Assert.assertEquals(MESSAGE, message.getContent());
	}

	@Test
	public void testSignatureDetachedNoArmor() throws Exception
	{
		AdaptrisMessage message = AdaptrisMessageFactory.getDefaultInstance().newMessage(MESSAGE);
		PGPSignService sign = getSignService(privateKey, PASSPHRASE, true, false);

		sign.doService(message);

		message = AdaptrisMessageFactory.getDefaultInstance().newMessage(message.getPayload());
		message.addMetadata("message", MESSAGE);
		PGPVerifyService verify = getVerifyService(publicKey, true);

		verify.doService(message);

		Assert.assertEquals(MESSAGE, message.getContent());
	}

	@Test
	public void testSignatureClear() throws Exception
	{
		AdaptrisMessage message = AdaptrisMessageFactory.getDefaultInstance().newMessage(MESSAGE);
		PGPSignService sign = getSignService(privateKey, PASSPHRASE, false, true);

		sign.doService(message);

		message = AdaptrisMessageFactory.getDefaultInstance().newMessage(message.getPayload());
		PGPVerifyService verify = getVerifyService(publicKey, false);

		verify.doService(message);

		Assert.assertEquals(MESSAGE, message.getContent());
	}

	private PGPSignService getSignService(PGPSecretKey key, String passphrase, boolean detached, boolean armor) throws Exception
	{
		PGPSignService service = new PGPSignService();
		service.setKey(new ConstantDataInputParameter(getKey(key)));
		service.setPassphrase(new ConstantDataInputParameter(passphrase));
		service.setDataToSign(new PayloadStreamInputParameter());
		service.setDetachedSignature(detached);
		service.setArmorEncoding(armor);
		service.setSignature(detached ? new PayloadStreamOutputParameter() : new StringPayloadDataOutputParameter());
		return service;
	}

	private PGPVerifyService getVerifyService(PGPPublicKey key, boolean detached) throws Exception
	{
		PGPVerifyService service = new PGPVerifyService();
		service.setKey(new ConstantDataInputParameter(getKey(key)));
		service.setSignedMessage(detached ? new MetadataDataInputParameter("message") : new PayloadStreamInputParameter());
		service.setSignature(detached ? new PayloadStreamInputParameter() : null);
		service.setUnsignedMessage(new StringPayloadDataOutputParameter());
		return service;
	}

	@Override
	protected Object retrieveObjectForSampleConfig()
	{
		return new PGPSignService();
	}
}
