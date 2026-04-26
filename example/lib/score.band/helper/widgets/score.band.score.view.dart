
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_rtmp_broadcaster_example/extensions/extensions.dart';

class ScoreBandScoreView extends ConsumerWidget {
  final int run;
  final int wicket;
  final String overCount;
  const ScoreBandScoreView({super.key, required this.run, required this.wicket, required this.overCount});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final textStyleForBand = TextStyle(
      fontFamily: 'RobotoCondensed',
      color: Colors.white,
    );
    return Row(
      children: [
        Text(
          '$run-$wicket',
          style: textStyleForBand.copyWith(
            fontSize: context.mobileWidth ? 12 : 32,
            fontWeight: FontWeight.w700,
            fontStyle: FontStyle.italic,
            color: const Color(0xFF120A7C),
          ),
        ),
        const SizedBox(width: 18),
        Text(
          overCount,
          style: textStyleForBand.copyWith(
            fontSize: context.mobileWidth ? 10 : 28,
            fontWeight: FontWeight.w700,
            fontStyle: FontStyle.italic,
            color: const Color(0xFF120A7C),
          ),
        ),
      ],
    );
  }
}
