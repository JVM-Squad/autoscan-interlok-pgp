package com.adaptris.security.pgp;

import com.adaptris.core.AdaptrisMessage;
import com.adaptris.core.CoreException;
import com.adaptris.core.ServiceException;
import com.adaptris.core.ServiceImp;
import com.adaptris.core.common.InputStreamWithEncoding;
import com.adaptris.core.common.MetadataStreamInputParameter;
import com.adaptris.core.common.PayloadStreamInputParameter;
import com.adaptris.core.common.PayloadStreamOutputParameter;
import com.adaptris.interlok.InterlokException;
import com.adaptris.interlok.config.DataInputParameter;
import com.adaptris.interlok.config.DataOutputParameter;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.InvalidParameterException;
import java.security.Security;
import java.security.SignatureException;

public class PGPVerifyService extends ServiceImp
{
	private static transient Logger log = LoggerFactory.getLogger(PGPVerifyService.class);

	private static final Charset CHARSET = Charset.forName("UTF-8");

	static
	{
		Security.addProvider(new BouncyCastleProvider());
	}

	@NotNull
	@Valid
	private DataInputParameter key = new MetadataStreamInputParameter();

	@NotNull
	@Valid
	private DataInputParameter signedMessage = new PayloadStreamInputParameter();

	@Valid
	private DataInputParameter signature = new MetadataStreamInputParameter();

	@Valid
	private DataOutputParameter unsignedMessage = new PayloadStreamOutputParameter();

	/**
	 * {@inheritDoc}.
	 */
	@Override
	public void doService(AdaptrisMessage message) throws ServiceException
	{
		try
		{
			Object key = this.key.extract(message);
			if (key instanceof String)
			{
				key = new ByteArrayInputStream(((String)key).getBytes(CHARSET));
			}
			if (!(key instanceof InputStream))
			{
				throw new InterlokException("Could not read public key");
			}
			Object data = this.signedMessage.extract(message);
			if (data instanceof String)
			{
				data = new ByteArrayInputStream(((String)data).getBytes(CHARSET));
			}
			if (!(data instanceof InputStream))
			{
				throw new InterlokException("Could not read signed message");
			}
			Object signature = null; // if the signature is not set them it can't be a detached signature
			if (this.signature != null)
			{
				signature = this.signature.extract(message);
				if (signature instanceof String)
				{
					signature = new ByteArrayInputStream(((String)signature).getBytes(CHARSET));
				}
				if (!(signature instanceof InputStream))
				{
					throw new InterlokException("Could not read signature");
				}
			}

			if (signature != null)
			{
				verifyDetached((InputStream)data, (InputStream)signature, (InputStream)key);
			}
			else
			{
				ByteArrayOutputStream unsignedMessage = new ByteArrayOutputStream();

				verifyClear((InputStream)data, (InputStream)key, unsignedMessage);

				try
				{
					this.unsignedMessage.insert(unsignedMessage.toString(CHARSET.toString()), message);
				}
				catch (ClassCastException e)
				{
					/* this.unsignedMessage was not expecting a String, must be an InputStreamWithEncoding */
					this.unsignedMessage.insert(new InputStreamWithEncoding(new ByteArrayInputStream(unsignedMessage.toByteArray()), null), message);
				}
			}
		}
		catch (Exception e)
		{
			log.error("An error occurred during PGP verification", e);
			throw new ServiceException(e);
		}
	}

	/**
	 * Set the private key for decryption.
	 *
	 * @param key The private key.
	 */
	public void setKey(DataInputParameter key)
	{
		this.key = key;
	}

	/**
	 * Get the private key for decryption.
	 *
	 * @return The private key.
	 */
	public DataInputParameter getKey()
	{
		return key;
	}

	/**
	 * Set the signed message to verify.
	 *
	 * @param signedMessage The signed message.
	 */
	public void setSignedMessage(DataInputParameter signedMessage)
	{
		this.signedMessage = signedMessage;
	}

	/**
	 * Get the signed message to verify.
	 *
	 * @return The signed message.
	 */
	public DataInputParameter getSignedMessage()
	{
		return signedMessage;
	}

	/**
	 * Set the signature to verify.
	 *
	 * @param signature The signature.
	 */
	public void setSignature(DataInputParameter signature)
	{
		this.signature = signature;
	}

	/**
	 * Get the signature to verify.
	 *
	 * @return The signature.
	 */
	public DataInputParameter getSignature()
	{
		return signature;
	}

	/**
	 * Set the unsigned message.
	 *
	 * @param message The message.
	 */
	public void setUnsignedMessage(DataOutputParameter message)
	{
		this.unsignedMessage = message;
	}

	/**
	 * Get the unsigned message.
	 *
	 * @return The message.
	 */
	public DataOutputParameter getUnsignedMessage()
	{
		return unsignedMessage;
	}

	/**
	 * {@inheritDoc}.
	 */
	@Override
	protected void initService()
	{
		/* unused */
	}

	/**
	 * {@inheritDoc}.
	 */
	@Override
	protected void closeService()
	{
		/* unused */
	}

	/**
	 * {@inheritDoc}.
	 */
	@Override
	public void prepare() throws CoreException
	{
		/* unused */
	}

	private static void verifyClear(InputStream in, InputStream key, ByteArrayOutputStream out) throws Exception
	{
		ArmoredInputStream aIn = new ArmoredInputStream(in);
		//
		// write out signed section using the local line separator.
		// note: trailing white space needs to be removed from the end of
		// each line RFC 4880 Section 7.1
		//
		ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
		int lookAhead = Utils.readInputLine(lineOut, aIn);
		byte[] lineSep = Utils.getLineSeparator();
		if (lookAhead != -1 && aIn.isClearText())
		{
			byte[] line = lineOut.toByteArray();
			out.write(line, 0, Utils.getLengthWithoutSeparatorOrTrailingWhitespace(line));
			out.write(lineSep);
			while (lookAhead != -1 && aIn.isClearText())
			{
				lookAhead = Utils.readInputLine(lineOut, lookAhead, aIn);
				line = lineOut.toByteArray();
				out.write(line, 0, Utils.getLengthWithoutSeparatorOrTrailingWhitespace(line));
				out.write(lineSep);
			}
		}
		else
		{
			// a single line file
			if (lookAhead != -1)
			{
				byte[] line = lineOut.toByteArray();
				out.write(line, 0, Utils.getLengthWithoutSeparatorOrTrailingWhitespace(line));
				out.write(lineSep);
			}
		}
		out.close();
		PGPPublicKeyRingCollection pgpRings = new PGPPublicKeyRingCollection(Utils.getDecoderStream(key), new JcaKeyFingerprintCalculator());
		JcaPGPObjectFactory pgpFact = new JcaPGPObjectFactory(aIn);
		PGPSignatureList p3 = (PGPSignatureList)pgpFact.nextObject();
		PGPSignature sig = p3.get(0);
		PGPPublicKey publicKey = pgpRings.getPublicKey(sig.getKeyID());
		sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), publicKey);
		//
		// read the input, making sure we ignore the last newline.
		//
		InputStream sigIn = new ByteArrayInputStream(out.toByteArray());
		lookAhead = Utils.readInputLine(lineOut, sigIn);
		Utils.processLine(sig, lineOut.toByteArray());
		if (lookAhead != -1)
		{
			do
			{
				lookAhead = Utils.readInputLine(lineOut, lookAhead, sigIn);
				sig.update((byte)'\r');
				sig.update((byte)'\n');
				Utils.processLine(sig, lineOut.toByteArray());
			}
			while (lookAhead != -1);
		}
		sigIn.close();
		if (!sig.verify())
		{
			throw new PGPException("Signature verification failed");
		}
	}

	private static void verifyDetached(InputStream inMessage, InputStream inSignature, InputStream key) throws Exception
	{
		inSignature = Utils.getDecoderStream(inSignature);
		JcaPGPObjectFactory pgpFact = new JcaPGPObjectFactory(inSignature);
		PGPSignatureList p3;
		Object o = pgpFact.nextObject();
		if (o instanceof PGPCompressedData)
		{
			PGPCompressedData c1 = (PGPCompressedData)o;
			pgpFact = new JcaPGPObjectFactory(c1.getDataStream());
			p3 = (PGPSignatureList)pgpFact.nextObject();
		}
		else
		{
			p3 = (PGPSignatureList)o;
		}
		PGPPublicKeyRingCollection pgpPubRingCollection = new PGPPublicKeyRingCollection(Utils.getDecoderStream(key), new JcaKeyFingerprintCalculator());
		PGPSignature sig = p3.get(0);
		PGPPublicKey pubKey = pgpPubRingCollection.getPublicKey(sig.getKeyID());
		sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), pubKey);
		int ch;
		while ((ch = inMessage.read()) >= 0)
		{
			sig.update((byte)ch);
		}
		inMessage.close();
		if (!sig.verify())
		{
			throw new PGPException("Signature verification failed");
		}
	}
}
