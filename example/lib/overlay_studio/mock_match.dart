import 'dart:math';

import 'package:flutter/material.dart';

/// Fake cricket match feeding the overlay scenarios. Everything here is made up.
class MockMatch {
  MockMatch({int? seed}) : _rng = Random(seed);

  final Random _rng;

  static const home = 'Dhaka Tigers';
  static const away = 'Chattogram Kings';

  static const batsmen = ['T. Iqbal', 'L. Das', 'S. Hossain', 'M. Rahim', 'A. Hossain', 'N. Hasan'];
  static const bowlers = ['T. Ahmed', 'M. Rahman', 'Shoriful', 'Taijul', 'Rishad', 'Hasan Mahmud'];
  static const fielders = ['N. Shanto', 'Afif', 'Towhid', 'Jaker Ali', 'Mehidy'];

  static const sponsors = [
    ('GreenTel', Color(0xFF2E7D32)),
    ('BanglaPay', Color(0xFF6A1B9A)),
    ('RoyalCola', Color(0xFFC62828)),
    ('SkyAir', Color(0xFF1565C0)),
  ];

  static const headlinesEnglish = [
    'Rain delay expected at 7:30 PM — covers on standby at the ground',
    'Next match: Dhaka Tigers vs Sylhet Strikers, Friday 3 PM',
    'Stadium gates open at 1 PM · free parking for ticket holders',
  ];
  static const headlineBangla = 'সরাসরি সম্প্রচার: ঢাকা টাইগার্স বনাম চট্টগ্রাম কিংস — ফাইনাল ম্যাচ, মিরপুর শেরে বাংলা স্টেডিয়াম';
  static const headlineArabic = 'بث مباشر: نهائي البطولة بين دكا تايجرز وشيتاغونغ كينغز';

  int runs = 112;
  int wickets = 3;
  int balls = 14 * 6 + 2;

  String get overs => '${balls ~/ 6}.${balls % 6}';
  String get scoreLine => '$home  $runs/$wickets  ($overs ov)';

  T pick<T>(List<T> list) => list[_rng.nextInt(list.length)];

  /// Advance one ball; returns what happened: 0–6 runs or -1 for a wicket.
  int nextBall() {
    balls++;
    final roll = _rng.nextInt(20);
    if (roll == 0) {
      wickets = min(9, wickets + 1);
      return -1;
    }
    final r = const [0, 0, 1, 1, 1, 2, 2, 3, 4, 4, 6][_rng.nextInt(11)];
    runs += r;
    return r;
  }

  String wicketText() {
    final b = pick(batsmen);
    return 'WICKET!  $b c ${pick(fielders)} b ${pick(bowlers)}  ${_rng.nextInt(60) + 5} (${_rng.nextInt(40) + 8})';
  }

  String playerCard() {
    final b = pick(batsmen);
    return '$b  ·  ${_rng.nextInt(70) + 10}* (${_rng.nextInt(45) + 10})  ·  SR ${(_rng.nextDouble() * 80 + 110).toStringAsFixed(1)}';
  }
}
