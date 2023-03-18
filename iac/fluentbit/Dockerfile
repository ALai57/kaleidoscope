# 2022-11-06 Changed the tag from :latest to :stable while debugging possible memory issues
FROM public.ecr.aws/aws-observability/aws-for-fluent-bit:stable
ADD extra.conf /extra.conf
ADD fb_metrics_parser.conf /fb_metrics_parser.conf
CMD ["/fluent-bit/bin/fluent-bit", "-e", "/fluent-bit/cloudwatch.so", "-c", "/extra.conf"]
