
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_rtmp_broadcaster_example/extensions/extensions.dart';

class ScoreBandVsTeam extends ConsumerWidget {
  const ScoreBandVsTeam({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // final battingInfo = mData.gameResult?.batting;
 


    final textStyleForBand = TextStyle(
      fontFamily: 'RobotoCondensed',
      color: Colors.white,
    );
    const matchTextShadow = [
      Shadow(
        color: Color(0x66000000), // black at ~40% opacity (const-friendly)
        offset: Offset(2, 2),
        blurRadius: 8,
      ),
    ];
    return Row(
      children: [
        Text(
          "TBA",
          style: textStyleForBand.copyWith(
            fontSize: context.mobileWidth ? 8 : 24,
            fontWeight: FontWeight.w700,
            shadows: matchTextShadow,
          ),
        ),
        const SizedBox(width: 10),
        Text(
          'VS',
          style: textStyleForBand.copyWith(
            fontSize: context.mobileWidth ? 8 : 24,
            shadows: matchTextShadow,
          ),
        ),
        const SizedBox(width: 10),
        Text(
          'XDD',
          style: textStyleForBand.copyWith(
            fontSize: context.mobileWidth ? 8 : 24,
            shadows: matchTextShadow,
          ),
        ),
      ],
    );
  }
}
