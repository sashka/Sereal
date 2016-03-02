package com.booking.sereal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.junit.Assert;

import com.booking.sereal.Utils.Function;

/**
 * To make the corpus of test files:
 * perl -I Perl/shared/t/lib/ -MSereal::TestSet -MSereal::Encoder -e'Sereal::TestSet::write_test_files("test_dir")'
 * 
 * This runs the Decoder/Encoder over every file in the supplied directory and tells you when the bytes the encoder
 * outputs do not match the bytes in the test file. The purpose is to check if roundtripping to Perl type
 * datastructures works.
 * 
 * If you pass a file as parameter it will do the same but do more detailed logging.
 * 
 */
public class TestCorpus {

	private static StructureDecoder sd = new StructureDecoder();
	private static Decoder dec;
	private static Encoder enc;
	static boolean writeEncoded = true;
	static boolean abortOnFirstError = false;
	static boolean testRoundtrip = false;
	static boolean verbose = false;

	static {
		String version = System.getenv().get("CORPUS_PROTO_VER");
		String compression = System.getenv().get("CORPUS_COMPRESS");

		DecoderOptions decoder_options = new DecoderOptions()
			.perlReferences(true)
			.perlAliases(true)
			.preserveUndef(true);
		dec = new Decoder( decoder_options );

		EncoderOptions encoder_options = new EncoderOptions()
			.perlReferences(true)
			.perlAliases(true);

		// parse version/compression
		if (version != null)
			encoder_options.protocolVersion(Integer.parseInt(version));
		if ("SRL_SNAPPY".equals(compression)) {
			encoder_options.compressionType(EncoderOptions.CompressionType.SNAPPY);
		} else if ("SRL_ZLIB".equals(compression)) {
			encoder_options.compressionType(EncoderOptions.CompressionType.ZLIB);
		} else if ("SRL_NONE".equals(compression)) {
			encoder_options.compressionType(EncoderOptions.CompressionType.NONE);
		} else if (compression != null) {
			throw new IllegalArgumentException("Unknown compression type '" + compression + "'");
		}

		enc = new Encoder( encoder_options );
	}

	/**
	 * Broken tests atm:
	 * 00035: I don't even...
	 * 00036: broken: unicode string that is actually encodable as latin1
	 * 00085: long unicode string that contains only latin1, so we can't roundtrip...
	 * 
	 * @param args
	 * @throws IOException
	 * @throws SerealException
	 */
	public static void main(String[] args) throws IOException {

		String manual = null;//"../test_dir/test_data_00029";

		if( args.length == 0 && manual == null ) {
			throw new UnsupportedOperationException( "Usage: Example [test_dir OR test_data_00XXXX]" );
		}

		final File target = new File( (manual == null ? args[0] : manual) ).getCanonicalFile(); // to absorb ".." in paths

		if( !target.exists() ) {
			throw new FileNotFoundException( "No such file or directory: " + target.getAbsolutePath() );
		}

		if( target.isDirectory() ) {
			System.out.println( "Running decoder on all test files in " + target.getCanonicalPath() );
			decodeAllTests( target );
		} else {
			verbose = true;
			System.out.println( "Decoding a single file: " + target.getAbsolutePath() );
			// more logging
			dec.debugTrace = true;
			enc.debugTrace = true;
			roundtrip( target );
		}

	}

	static int ok_dec = 0;
	static int ok_enc = 0;
	static int ok_round = 0;

	private static boolean roundtrip(File target) {

		enc.reset();
		dec.reset();

		try {
			System.out.print( "Testing " + target.getName() + " -" );

			System.out.print( " Decode: " );
			Object data = dec.decodeFile( target );
			System.out.print( "OK" );
			ok_dec++;
			if( verbose ) {
				System.out.println( "\nDecoding Done: " + Utils.dump( data ) + "\n" );
			}
			System.out.print( " Encode: " );
			ByteBuffer encoded = enc.write( data );
			System.out.print( "OK" );
			ok_enc++;
			if( writeEncoded ) {
				FileOutputStream fos = new FileOutputStream( new File( target.getAbsolutePath() + "-java.out" ) );
				fos.write( encoded.array() );
				fos.close();
			}

			FileInputStream fis = new FileInputStream( target );
			ByteBuffer buf = ByteBuffer.allocate( (int) target.length() );
			fis.getChannel().read( buf );
			fis.close();
			if( verbose ) {
				System.out.println();
				System.out.println( "From file: " + Utils.hexStringFromByteArray( buf.array(), 4 ) );
				System.out.println( "Encoded  : " + Utils.hexStringFromByteArray( encoded.array(), 4 ) );
				System.out.println( "\nStructure: " + sd.decodeFile( target ) );
			}
			if (testRoundtrip) {
				System.out.print( " Roundtrip: " );
				Assert.assertArrayEquals( "Roundtrip fail for: " + target.getName(), buf.array(), encoded.array() );
				System.out.println( "OK" );
				ok_round++;
			} else {
				System.out.println();
			}
		} catch (SerealException e) {
			e.printStackTrace( System.out );
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} catch (AssertionError a) {
			if( verbose ) {
				System.out.println( a.getMessage() );
			} else {
				System.out.println( "Fail" );
			}
			return false;
		} catch (Exception e) {
			if( verbose ) {
				e.printStackTrace();
			} else {
				System.out.println( "Fail" );
			}
			return false;
		}

		return true;

	}

	protected static void decodeAllTests(final File test_dir) {

		// get all files called test_data_...
		String[] filenames = test_dir.list( new FilenameFilter() {

			@Override
			public boolean accept(File arg0, String arg1) {
				return arg1.contains( "test_data_" );
			}
		} );

		// turn them into Files
		List<File> tests = Utils.map( filenames, new Function<String, File>() {

			@Override
			public File apply(String o) {
				return new File( test_dir, o );
			}

		} );

		for(File test : tests) {

			boolean success = roundtrip( test );
			if( abortOnFirstError && !success ) {
				System.out.println( "Aborting after first error" );
				return;
			}

		}
		System.out.printf( "Decoded: %d/%d = %.2f%%\n", ok_dec, tests.size(), ((double) 100 * ok_dec / tests.size()) );
		System.out.printf( "Encoded: %d/%d = %.2f%%\n", ok_enc, tests.size(), ((double) 100 * ok_enc / tests.size()) );
		if (testRoundtrip)
			System.out.printf( "Roundtrip: %d/%d = %.2f%%\n", ok_round, tests.size(), ((double) 100 * ok_round / tests.size()) );

	}

}
