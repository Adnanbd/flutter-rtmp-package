import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_rtmp_broadcaster_example/extensions/extensions.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/painter/score.part.of.band.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.ball.by.ball.view.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.batsman.info.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.bowler.team.info.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.score.view.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.team.img.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.vs.team.dart';
import 'package:flutter_svg/svg.dart';

class ScoreBandView extends ConsumerWidget {
  const ScoreBandView({super.key, required this.repaintBoundaryKey, required this.homeScore});
  final GlobalKey repaintBoundaryKey;
  final int homeScore;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // final isGameStarted = ref.watch(gameStatusToggle);
    // final b = '${mData.gameResult?.fielding?.balls}';

    bool isStartedd = true;

    var isStartedFinal = isStartedd;

    final String? battingTeamImg;
    final String? bowlingTeamImg;

    // Widget bowlinTeamData(){
    //   return mData.gameResult?.fielding?.livescore == null
    //     ? const SizedBox()
    //     : Padding(
    //         padding: const EdgeInsets.symmetric(vertical: 10.0),
    //         child: Text(
    //           '${mData.gameResult?.fielding?.livescore} / ${mData.gameResult?.fielding?.wicketstaken} - Over: ${OverUtils.normalizeOver(b)} (${mData.gameConfiguration?.over ?? mData.tmatchOvers?.toString() ?? '9999'})',
    //           // '${startMatchData.gameResult?.fielding?.livescore} / ${startMatchData.gameResult?.fielding?.wicketstaken} - Over: $fetchedOverCount (${gameListModel.gameConfiguration?.over ?? startMatchData.tmatchOvers?.toString() ?? '9999'})',
    //           style: context.text.titleLarge?.copyWith(
    //             fontSize: fontSize,
    //           ),
    //         ),
    //       );
    // }

    const assetName = 'assets/onlylogovector.svg';
    final Widget svg = SvgPicture.asset(
      assetName,
      semanticsLabel: 'Spordium Logo',
      height: context.mobileWidth ? 60 : 115,
    );
    const assetName1 = 'assets/logobg.svg';
    final Widget svg1 = SvgPicture.asset(
      assetName1,
      semanticsLabel: 'Spordium Logo1',
      height: context.mobileWidth ? 45 : 90,
    );

    if (isStartedFinal == false) {
      return const SizedBox();
    }

    return RepaintBoundary(
      key: repaintBoundaryKey,
      
      child: LayoutBuilder(
        builder: (context, constraints) {
          final availableHeight = constraints.maxHeight.isInfinite ? context.height : constraints.maxHeight;
          final availableWidth = constraints.maxWidth.isInfinite ? context.width : constraints.maxWidth;

          double width, height;
          var mediumMode = false;
          print('availableWidth = $availableWidth');
          width = availableWidth;
          height = availableHeight;

          if (availableWidth <= 1100 && context.mobileWidth == false) {
            mediumMode = true;
          }

          Size scoreWhiteSize;

          if (context.mobileWidth) {
            scoreWhiteSize = Size(80, 22);
          } else if (!context.mobileWidth && context.isMobile) {
            scoreWhiteSize = Size(200, 50);
          } else if (mediumMode) {
            scoreWhiteSize = Size(200, 50);
          } else {
            scoreWhiteSize = Size(245, 50);
          }

          double teamLogoSize;
          if (context.mobileWidth) {
            teamLogoSize = 20;
          } else if (mediumMode) {
            teamLogoSize = 50;
          } else {
            teamLogoSize = 70;
          }

          double teamImagePadding;
          if (context.mobileWidth || context.isMobile || mediumMode) {
            teamImagePadding = 10;
          } else {
            teamImagePadding = 25;
          }
          double playerNameFontSize;
          if (context.mobileWidth) {
            playerNameFontSize = 10;
          } else if (mediumMode) {
            playerNameFontSize = 16;
          } else {
            playerNameFontSize = 18;
          }

          return Stack(
            alignment: Alignment.center,
            clipBehavior: Clip.none,
            children: [
              Container(
                height: context.mobileWidth ? 45 : 90,
                // width: context.mobileWidth ? context.width * .8 : 500,
                width: width,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [
                      const Color(0xFF43D500), //#120A7C
                      const Color(0xFF120A7C),
                      const Color(0xFF43D500),
                    ],
                    // stops: [0.5, 0.5],
                  ),
                  borderRadius: BorderRadius.circular(context.mobileWidth ? 0 : 16),
                ),
                // padding: EdgeInsets.all(10),
              ),
              Positioned(bottom: 0, child: svg1),
              Positioned(bottom: 0, child: svg),
              Positioned(
                top: 0,
                child: SizedBox(
                  width: width,
                  height: (context.mobileWidth ? 45 : 90) - (context.mobileWidth ? 18 : 35),
                  child: Row(
                    children: [
                      SizedBox(width: teamImagePadding + teamLogoSize),
                      SizedBox(width: 7),
                      Expanded(
                        child: Builder(
                          builder: (context) {
                            final playerName = 'ASAD UZZAMAN';
                            return ScorebandBatsmanInfo(
                              height: (context.mobileWidth ? 45 : 90) - (context.mobileWidth ? 18 : 35),
                              batsmanName: playerName,
                              runs: '12',
                              balls: '2',
                              isStrike: true,
                              batsmanImage: 'playerImage',
                              mediumMode: mediumMode,
                              fontSize: playerNameFontSize,
                            );
                          },
                        ),
                      ),
                      SizedBox(width: scoreWhiteSize.width),
                      Expanded(
                        child: ScorebandBowlerTeamInfo(
                          playerName: 'MD NAIM',
                          wicket: 1,
                          runsGiven: 12,
                          overCount: '1.2',
                          mediumMode: mediumMode,
                          fontSize: playerNameFontSize,
                        ),
                      ),
                      SizedBox(width: 3),
                      SizedBox(width: teamImagePadding + teamLogoSize),
                    ],
                  ),
                ),
              ),
              Positioned(
                bottom: 0,
                child: Container(
                  width: width,
                  height: context.mobileWidth ? 18 : 35,
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        Colors.transparent,
                        const Color(0xFF120A7C),
                        const Color(0xFF120A7C),
                        Colors.transparent,
                      ],
                      stops: [0.0, 0.25, 0.75, 1.0],
                    ),
                  ),
                ),
              ),
              Positioned(
                bottom: 0,
                child: SizedBox(
                  width: width,
                  height: context.mobileWidth ? 18 : 35,
                  child: Row(
                    children: [
                      SizedBox(width: teamImagePadding + teamLogoSize),
                      SizedBox(width: 7),
                      Expanded(
                        child: ScorebandBatsmanInfo(
                          height: context.mobileWidth ? 18 : 35,
                          batsmanName: 'ROKIB HASSAN',
                          runs: '11',
                          balls: '4',
                          isStrike: false,
                          batsmanImage: 'playerImage',
                          mediumMode: mediumMode,
                          fontSize: playerNameFontSize,
                        ),
                      ),
                      SizedBox(width: scoreWhiteSize.width),
                      Expanded(
                        child: ScorebandBallByBallView(mediumMode: mediumMode, fontSize: playerNameFontSize),
                      ),
                      SizedBox(width: 3),
                      SizedBox(width: teamImagePadding + teamLogoSize),
                    ],
                  ),
                ),
              ),
              Positioned(
                bottom: 0,
                child: CustomPaint(size: scoreWhiteSize, painter: RPSCustomPainter()),
              ),
              Positioned(
                left: teamImagePadding,
                child: ScorebandTeamImage(
                  imgUrl:
                      'https://png.pngtree.com/png-vector/20191224/ourmid/pngtree-spartan-red-team-png-image_2093590.jpg',
                  size: teamLogoSize,
                ),
              ),
              Positioned(
                right: teamImagePadding,
                child: ScorebandTeamImage(
                  imgUrl:
                      'https://thumbs.dreamstime.com/b/vector-art-dream-team-logo-featuring-crossed-baseball-bats-shield-stars-perfect-sports-dynamic-showcasing-ideal-406389566.jpg',
                  size: teamLogoSize,
                ),
              ),
              Positioned(
                bottom: context.mobileWidth ? 4 : 6,
                child: ScoreBandScoreView(run: homeScore, wicket: 4, overCount: '9.7'),
              ),
              Positioned(top: 4, child: ScoreBandVsTeam()),
            ],
          );
        },
      ),
    );
  }
}

String? getInitialsWithDot(String? text) {
  return text
      ?.trim()
      .split(RegExp(r'\s+'))
      .where((word) => word.isNotEmpty)
      .map((word) => word[0].toUpperCase())
      .join('.');
}
