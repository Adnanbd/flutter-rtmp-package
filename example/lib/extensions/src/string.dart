part of '../extensions.dart';

extension StringUtils on String {
  bool get isEmail => _emailRegularExpression.hasMatch(toLowerCase());

  int get wordCount => words.length;

  List<String> get words => split(' ');

  String get capitalize => '${this[0].toUpperCase()}${substring(1)}';

  bool hasMatch(String v) => toLowerCase().contains(v.toLowerCase());

  String get toErrors {
    try {
      final json = Map<String, dynamic>.from(jsonDecode(this));
      final errors = (json['errors'] as List).map((e) => e.toString()).toList();
      return errors.join('\n');
    } catch (e) {
      return 'Something went wrong!';
    }
  }

  List<String> get toErrorsList {
    try {
      final d = ErrorsModel.fromRawJson(this);
      return d.errors ?? [];
    } catch (e) {
      return ['Something went wrong!'];
    }
  }

  String get prettyJson {
    try {
      const encoder = JsonEncoder.withIndent('  ');
      final map = jsonDecode(this);
      return encoder.convert(map);
    } catch (e) {
      return this;
    }
  }

  // camelCaseToTitleCase => Camel Case To Title Case
  String get toTitleCase {
    final spaced = replaceAllMapped(
      RegExp(r'([a-z])([A-Z])'),
      (match) => '${match.group(1)} ${match.group(2)}',
    );
    return spaced[0].toUpperCase() + spaced.substring(1);
  }

  /// Makes Like This
  String get standardize {
    // Regular expression to find camel case words
    final regex = RegExp(r'([a-z])([A-Z])');

    // Replace camel case boundaries with a space and lowercase the result
    String formatted = replaceAllMapped(regex, (match) {
      return '${match.group(1)} ${match.group(2)}';
    });

    // Capitalize the first letter and make the rest lowercase
    return formatted[0].toUpperCase() + formatted.substring(1).toLowerCase();
  }

  /// 1,000
  String? get toIndianFormat {
    if (isEmpty) return '0';

    // Split on decimal point
    final parts = split('.');
    String intPart = parts[0].replaceAll(RegExp(r'[^0-9]'), '');
    String decimalPart = parts.length > 1 ? '.${parts[1]}' : '';

    if (intPart.isEmpty) return this; // fallback if no valid int part

    if (intPart.length <= 3) return intPart + decimalPart;

    String lastThree = intPart.substring(intPart.length - 3);
    String rest = intPart.substring(0, intPart.length - 3);

    List<String> formattedParts = [];

    while (rest.length > 2) {
      formattedParts.insert(0, rest.substring(rest.length - 2));
      rest = rest.substring(0, rest.length - 2);
    }

    if (rest.isNotEmpty) {
      formattedParts.insert(0, rest);
    }

    String formattedInt = '${formattedParts.join(',')},$lastThree';

    return formattedInt + decimalPart;
  }

  String get withHttpsPrefix {
    if (!startsWith('http://') && !startsWith('https://')) {
      return 'https://$this';
    }
    return this;
  }

  String get formatPhoneNumber {
    String phone = this;

    // Remove any spaces or underscores first
    phone = phone.replaceAll(RegExp(r'[\s_]'), '');

    // If the number starts with +880, remove it
    if (phone.startsWith('+880')) {
      phone = phone.replaceFirst('+880', '');
    }
    // If the number starts with 880 (without +), remove that too
    else if (phone.startsWith('880')) {
      phone = phone.replaceFirst('880', '');
    }

    // If it still starts with 0 after country code removal, remove it
    if (phone.startsWith('0')) {
      phone = phone.substring(1);
    }

    return phone;
  }

  String get capitalizeFirst {
    if (isEmpty) return this;
    return this[0].toUpperCase() + substring(1).toLowerCase();
  }

  String get cleanClubUrl {
    if (isEmpty) return this;
    final cleaned = replaceFirst(RegExp(r'^https?:\/\/'), '');
    if (cleaned == 'spordium.com/' || cleaned == 'spordium.com') {
      return '';
    }
    return cleaned;
  }

  String truncateAt(int n) {
    if (trim().isEmpty || n <= 0) return '';
    if (length <= n) return this;           // no truncation, no dots
    return '${substring(0, n).trimRight()}...';  // truncated, dots appended
  }


  String get capitalizeWords {
    if (isEmpty) return this;
    return split(' ').map((word) => word.isEmpty ? word : word[0].toUpperCase() + word.substring(1)).join(' ');
  }
}

String pluralize(
  int number,
  String form1,
  String form2, [
  String? form3,
]) {
  final num = number % 100;

  if (num >= 11 && num <= 19) {
    return form3 ?? form2;
  }

  final i = num % 10;

  switch (i) {
    case 1:
      return form1;
    case 2:
    case 3:
    case 4:
      return form2;
    default:
      return form3 ?? form2;
  }
}

final RegExp _emailRegularExpression = RegExp(
    r"^((([a-z]|\d|[!#\$%&'\*\+\-\/=\?\^_`{\|}~]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])+(\.([a-z]|\d|[!#\$%&'\*\+\-\/=\?\^_`{\|}~]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])+)*)|((\x22)((((\x20|\x09)*(\x0d\x0a))?(\x20|\x09)+)?(([\x01-\x08\x0b\x0c\x0e-\x1f\x7f]|\x21|[\x23-\x5b]|[\x5d-\x7e]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(\\([\x01-\x09\x0b\x0c\x0d-\x7f]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF]))))*(((\x20|\x09)*(\x0d\x0a))?(\x20|\x09)+)?(\x22)))@((([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])*([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])))\.)+(([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])*([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])))$");
