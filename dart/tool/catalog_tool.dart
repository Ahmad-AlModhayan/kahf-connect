// Kahf Connect catalog keys and signatures.
//
//   dart run tool/catalog_tool.dart keygen <private-key-file>
//       Writes a new Ed25519 private key (base64 seed) to the file and prints
//       the public key to add to KahfKeys.catalog. Keep the file out of every
//       repository and backed up.
//   dart run tool/catalog_tool.dart sign <catalog.json> <private-key-file>
//       Writes <catalog.json>.sig: the base64 signature of the file's bytes.
//   dart run tool/catalog_tool.dart public <private-key-file>
//       Prints the public key of an existing private key.
// ignore_for_file: avoid_print
import 'dart:convert';
import 'dart:io';

import 'package:cryptography/cryptography.dart';

Future<void> main(List<String> args) async {
  final algorithm = Ed25519();
  switch (args) {
    case ['keygen', final out]:
      if (File(out).existsSync()) {
        stderr.writeln('$out exists; not overwriting a key');
        exit(2);
      }
      final pair = await algorithm.newKeyPair();
      final seed = await pair.extractPrivateKeyBytes();
      File(out)
        ..createSync(recursive: true)
        ..writeAsStringSync(base64.encode(seed));
      final pub = await pair.extractPublicKey();
      print(base64.encode(pub.bytes));
    case ['public', final keyFile]:
      final pair = await _pair(algorithm, keyFile);
      print(base64.encode((await pair.extractPublicKey()).bytes));
    case ['sign', final catalog, final keyFile]:
      final pair = await _pair(algorithm, keyFile);
      final bytes = File(catalog).readAsBytesSync();
      final sig = await algorithm.sign(bytes, keyPair: pair);
      File('$catalog.sig').writeAsStringSync(base64.encode(sig.bytes));
      print('signed $catalog (${bytes.length} bytes)');
    default:
      stderr.writeln(
          'usage: keygen <key> | public <key> | sign <catalog.json> <key>');
      exit(64);
  }
}

Future<SimpleKeyPair> _pair(Ed25519 algorithm, String keyFile) => algorithm
    .newKeyPairFromSeed(base64.decode(File(keyFile).readAsStringSync().trim()));
