package myproject;

import com.pulumi.Pulumi;
import com.pulumi.asset.FileArchive;
import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.PolicyArgs;
import com.pulumi.aws.autoscaling.inputs.GroupLaunchTemplateArgs;
import com.pulumi.aws.autoscaling.inputs.GroupTagArgs;
import com.pulumi.aws.cloudwatch.MetricAlarm;
import com.pulumi.aws.cloudwatch.MetricAlarmArgs;
import com.pulumi.aws.dynamodb.DynamodbFunctions;
import com.pulumi.aws.dynamodb.Table;
import com.pulumi.aws.dynamodb.TableArgs;
import com.pulumi.aws.dynamodb.inputs.TableAttributeArgs;
import com.pulumi.aws.ec2.Vpc;
import com.pulumi.aws.ec2.VpcArgs;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementPrincipalArgs;
import com.pulumi.aws.lambda.Function;
import com.pulumi.aws.lambda.FunctionArgs;
import com.pulumi.aws.lambda.Permission;
import com.pulumi.aws.lambda.PermissionArgs;
import com.pulumi.aws.lambda.inputs.FunctionEnvironmentArgs;
import com.pulumi.aws.lb.*;
import com.pulumi.aws.lb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.lb.inputs.LoadBalancerSubnetMappingArgs;
import com.pulumi.aws.lb.inputs.TargetGroupHealthCheckArgs;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.inputs.RecordAliasArgs;
import com.pulumi.aws.sns.TopicArgs;
import com.pulumi.aws.sns.TopicSubscription;
import com.pulumi.aws.sns.TopicSubscriptionArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.gcp.serviceaccount.Account;
import com.pulumi.gcp.serviceaccount.AccountArgs;
import com.pulumi.gcp.serviceaccount.Key;
import com.pulumi.gcp.serviceaccount.KeyArgs;
import com.pulumi.gcp.storage.Bucket;
import com.pulumi.gcp.storage.BucketArgs;
import com.pulumi.gcp.storage.BucketIAMBinding;
import com.pulumi.gcp.storage.BucketIAMBindingArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import static com.pulumi.codegen.internal.Serialization.*;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

public class App {
    static Logger logger = Logger.getLogger(App.class.getName());
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            var config = ctx.config();
            var data = config.requireObject("data", Map.class);

            var getData = data.get("name");
            if(null == getData || getData.toString().isEmpty()){
                getData = UUID.randomUUID().toString().replace("-", "");
            }
            String vpcString = getData.toString();
            var cidrBlockId = data.get("cidr-block");
//            if(null == cidrBlockId || cidrBlockId.toString().isEmpty()){
//                cidrBlockId = "cidr-block2";
//            }
            String cidr = cidrBlockId.toString();
            var domain=data.get("sub_domain");
            var imageid=(String)data.get("ami_id");
            var zone_id=data.get("zoneId");
            var vpcBase = new Vpc(vpcString,
                    VpcArgs.builder()
                            .cidrBlock(cidr)
                            .enableDnsHostnames(true)
                            .instanceTenancy("default")
                            .tags(Map.of("Name",vpcString))
                            .build());
            var getSubnets = data.get("num_of_subnets");
            int num;
            if(null ==getSubnets || Double.parseDouble(getSubnets.toString()) <= 0 ){
                num = 2;
            }else{
                num = (int) Double.parseDouble(getSubnets.toString());
            }
      //      var publicCidr = data.get("cidr-block");
//            if(null == publicCidr || publicCidr.toString().isEmpty()){
//                publicCidr = "public-cidr";
//            }

            Output<GetAvailabilityZonesResult> availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());


            //Object finalPublicCidr = publicCidr;
            availabilityZones.applyValue(availabilityZonesResult -> {
                int totalZones = availabilityZonesResult.names().size();
                List<String> strings = calculateSubnets(cidr,totalZones*2);
                List<Subnet> totalPublicSubnets = createPublicSubnets(num,vpcString,vpcBase,availabilityZonesResult.names(),totalZones,strings);
                Subnet firstSubnet = totalPublicSubnets.get(0);
//                Output<String> securityGroupId = securityGroup(vpcBase, "MyVpc", data);
//                Instance ec2Instance = instanceEc2("MyVpc", securityGroupId,firstSubnet, data);
                List<Subnet> privateSubNetList =createPrivateSubnets(num,vpcString,vpcBase,availabilityZonesResult.names(),totalZones,strings);
                ParameterGroup parameterGroup = new ParameterGroup("default", ParameterGroupArgs.builder()
                        .family("mariadb10.4")
                        .tags(Map.of("Name", "paramGroup"))
                        .build());
                Output<String> loadBalancer_securityGroupId = LB_securityGroup(vpcBase, "MyVpc", data);


                Output<String> securityGroupId = securityGroup(vpcBase, "MyVpc", data,loadBalancer_securityGroupId);

                com.pulumi.aws.rds.Instance rdsInstance = instanceDB(vpcBase,"MyVpc",securityGroupId, privateSubNetList, parameterGroup, data);

                Output<String> userDATA= userdataGenerater(rdsInstance);
                var roleIAM=new Role("iamRole", RoleArgs.builder().assumeRolePolicy(serializeJson(jsonObject(
                                jsonProperty("Version", "2012-10-17"),
                                jsonProperty("Statement", jsonArray(jsonObject(
                                        jsonProperty("Action", "sts:AssumeRole"),
                                        jsonProperty("Effect", "Allow"),
                                        jsonProperty("Sid", "IamPassRole"),
                                        jsonProperty("Principal", jsonObject(
                                                jsonProperty("Service", "ec2.amazonaws.com")
                                        ))
                                )))
                        )))
                        .tags(Map.of("tag-key", "tag-value"))
                        .build());


                var rolePolicy= new RolePolicyAttachment("cloudwatch-agent-policy-attachment", RolePolicyAttachmentArgs.builder()
                        .policyArn("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
                        .role(roleIAM.name())
                        .build());
                var rolePolicySNS= new RolePolicyAttachment("cloudwatch-agent-policy-attachmentSNS", RolePolicyAttachmentArgs.builder()
                        .policyArn("arn:aws:iam::aws:policy/AmazonSNSFullAccess")
                        .role(roleIAM.name())
                        .build());
                InstanceProfile instanceProfile = new InstanceProfile("Instance_Profile", InstanceProfileArgs.builder()
                        .role(roleIAM.name())
                        .build());



//                Instance ec2Instance = instanceEc2("MyVpc", securityGroupId, firstSubnet, data, userDATA, instanceProfile);
//                Output<String>  ec2Ip= ec2Instance.publicIp();

//                var record=new Record("Dev_record", RecordArgs.builder().zoneId((String) zone_id).name((String) domain).type("A").ttl(60).records(ec2Ip.applyValue(v->new ArrayList<>(Collections.singleton(v)))).
//                        build());


                LaunchTemplate template=launch_template(securityGroupId,userDATA,instanceProfile,data,firstSubnet);
                var lb= new com.pulumi.aws.lb.LoadBalancer("loadbalancer", LoadBalancerArgs.builder().loadBalancerType("application").
                        securityGroups(loadBalancer_securityGroupId.applyValue(Collections::singletonList))
                        .subnetMappings(LoadBalancerSubnetMappingArgs.builder()
                                        .subnetId(totalPublicSubnets.get(0).id()).build(),
                                LoadBalancerSubnetMappingArgs.builder()
                                        .subnetId(totalPublicSubnets.get(1).id())
                                        .build())
                        .build());
                var target = new  com.pulumi.aws.lb.TargetGroup("target", TargetGroupArgs.builder().targetType("instance")
                        .port(8080)
                        .protocol("HTTP")
                        .vpcId(vpcBase.id()).
                        healthCheck(TargetGroupHealthCheckArgs.builder().path("/healthz").protocol("HTTP").interval(60).timeout(50).matcher("200").port("8080").build())
                        .build());

                var frontEndListener = new com.pulumi.aws.lb.Listener("frontEndListener", ListenerArgs.builder()
                        .loadBalancerArn(lb.arn())
                        .port(443)
                        .protocol("HTTPS")
                        .certificateArn(data.get("certificate_arn").toString())
                        .defaultActions(ListenerDefaultActionArgs.builder().targetGroupArn(target.arn()).type("forward")
                                .build())
                        .build());
                var autoScalingGroup = new com.pulumi.aws.autoscaling.Group("Auto_scaling_Group", GroupArgs.builder().
                        minSize(1).maxSize(3). healthCheckGracePeriod(300)
                        .healthCheckType("ELB")
                        .forceDelete(false).terminationPolicies(Collections.singletonList("OldestInstance")).metricsGranularity("1Minute").
                        defaultCooldown(60).desiredCapacity(1).targetGroupArns(target.arn().applyValue(List::of)).
                        launchTemplate(GroupLaunchTemplateArgs.builder()
                                .id(template.id())
                                .version("$Latest")
                                .build()).
                        vpcZoneIdentifiers(Output.all(totalPublicSubnets.stream().map(Subnet::id).collect(toList()))).
                        tags(GroupTagArgs.builder()
                                        .key("Application")
                                        .value("webApp")
                                        .propagateAtLaunch(true)
                                        .build()).build());

                var scale_up = new com.pulumi.aws.autoscaling.Policy("scale-up-policy", PolicyArgs.builder()
                        .scalingAdjustment(1)
                        .adjustmentType("ChangeInCapacity")
                        .policyType("SimpleScaling")
                        .cooldown(300)
                        .autoscalingGroupName(autoScalingGroup.name())
                        .build());
                var scale_down = new com.pulumi.aws.autoscaling.Policy("scale-down-policy", PolicyArgs.builder()
                        .scalingAdjustment(-1)
                        .adjustmentType("ChangeInCapacity")
                        .policyType("SimpleScaling")
                        .cooldown(300)
                        .autoscalingGroupName(autoScalingGroup.name())
                        .build());
                var scaleup = new MetricAlarm("scale-up-alarm",
                        MetricAlarmArgs.builder()
                                .comparisonOperator("GreaterThanOrEqualToThreshold")
                                .evaluationPeriods(2)
                                .metricName("CPUUtilization")
                                .namespace("AWS/EC2")
                                .period(60)
                                .statistic("Average").threshold(5.0)
                                .alarmDescription("Alarm if server CPU too high")
                                .alarmActions(scale_up.arn().applyValue(Collections::singletonList))
                                .dimensions(autoScalingGroup.name().applyValue(s -> Map.of("AutoScalingGroupName", s)))
                                .build()
                );
                var scaledown = new MetricAlarm("scale-down-alarm",
                        MetricAlarmArgs.builder()
                                .comparisonOperator("LessThanOrEqualToThreshold")
                                .evaluationPeriods(2)
                                .metricName("CPUUtilization")
                                .namespace("AWS/EC2")
                                .period(60)
                                .statistic("Average").threshold(3.0)
                                .alarmDescription("Alarm if server CPU too low")
                                .alarmActions(scale_down.arn().applyValue(Collections::singletonList))
                                .dimensions(autoScalingGroup.name().applyValue(s -> Map.of("AutoScalingGroupName", s)))
                                .build());
                var record=new Record("Dev_record", RecordArgs.builder().
                        zoneId((String) zone_id).name((String) domain).type("A")
                        .aliases(RecordAliasArgs.builder()
                                .name(lb.dnsName())
                                .zoneId(lb.zoneId())
                                .evaluateTargetHealth(true)
                                .build())
                        .build());
                var sns_topic=new com.pulumi.aws.sns.Topic("SNS_TOPIC_creation", TopicArgs.builder().name("Assignment_SNS").displayName("assignment_sns")
                        .build());
                data.put("sns_topic_arn",sns_topic.arn());
                Bucket assignmentStorage = new Bucket("assignment-storage", BucketArgs.builder()
                        .forceDestroy(true)
                        .publicAccessPrevention("enforced")
                        .location("US")
                        .build());
                var serviceAccount = new Account("serviceAccount", AccountArgs.builder()
                        .accountId("assignment")
                        .displayName("Service Account")
                        .build());
                var mykey = new Key("mykey", KeyArgs.builder()
                        .serviceAccountId(serviceAccount.name())
                        .publicKeyType("TYPE_X509_PEM_FILE")
                        .build());
                var storageBinding = new BucketIAMBinding("storageAdminForLambda", BucketIAMBindingArgs.builder()
                        .role("roles/storage.admin")
                        .members(serviceAccount.email().applyValue(email -> "serviceAccount:" + email).applyValue(Collections::singletonList))
                        .bucket(assignmentStorage.id())
//                    .serviceAccountId(serviceAccount.id())
                        .build());
                final var assumeRole = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                        .statements(GetPolicyDocumentStatementArgs.builder()
                                .effect("Allow")
                                .principals(GetPolicyDocumentStatementPrincipalArgs.builder()
                                        .type("Service")
                                        .identifiers("lambda.amazonaws.com")
                                        .build())
                                .actions("sts:AssumeRole")
                                .build())
                        .build());


                var iamForLambda = new Role("iamForLambda", RoleArgs.builder()
                        .assumeRolePolicy(assumeRole.applyValue(getPolicyDocumentResult -> getPolicyDocumentResult.json()))
                        .build());

                final var lambdaLoggingPolicyDocument = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                        .statements(GetPolicyDocumentStatementArgs.builder()
                                .effect("Allow")
                                .actions(
                                        "logs:CreateLogGroup",
                                        "logs:CreateLogStream",
                                        "logs:PutLogEvents")
                                .resources("arn:aws:logs:*:*:*")
                                .build())
                        .build());

                final var dynamoDBPolicyDocument = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                        .statements(GetPolicyDocumentStatementArgs.builder()
                                .effect("Allow")
                                .actions(
                                        "dynamodb:PutItem",
                                        "dynamodb:GetItem",
                                        "dynamodb:GetItem",
                                        "dynamodb:DeleteItem",
                                        "dynamodb:UpdateItem")
                                .resources("arn:aws:dynamodb:*:*:*")
                                .build())
                        .build());
                String tableName = "assignment-submissions";
                var table = new Table(tableName, TableArgs.builder()
                        .name(tableName)
                        .billingMode("PROVISIONED")
                        .attributes(TableAttributeArgs.builder()
                                .name("submission_id")
                                .type("S")
                                .build())
//
                        .tags(Map.ofEntries(
                                Map.entry("Environment", "development"),
                                Map.entry("Name", "assignment-submissions")
                        ))
                        .hashKey("submission_id")
                        .writeCapacity(5)
                        .readCapacity(5)
                        .build());
                var lambdaLoggingPolicy = new Policy("lambdaLoggingPolicy", com.pulumi.aws.iam.PolicyArgs.builder()
                        .path("/")
                        .description("IAM policy for logging from a lambda")
                        .policy(lambdaLoggingPolicyDocument.applyValue(getPolicyDocumentResult -> getPolicyDocumentResult.json()))
                        .build());

                var dynamoDBPolicy = new Policy("dynamoDBPolicy", com.pulumi.aws.iam.PolicyArgs.builder()
                        .path("/")
                        .description("IAM policy for dynamoDB")
                        .policy(dynamoDBPolicyDocument.applyValue(getPolicyDocumentResult -> getPolicyDocumentResult.json()))
                        .build());

                var lambdaLogs = new RolePolicyAttachment("lambdaLogs", RolePolicyAttachmentArgs.builder()
                        .role(iamForLambda.name())
                        .policyArn(lambdaLoggingPolicy.arn())
                        .build());

                var dynamoDB = new RolePolicyAttachment("dynamoDB", RolePolicyAttachmentArgs.builder()
                        .role(iamForLambda.name())
                        .policyArn(dynamoDBPolicy.arn())
                        .build());

                Map<String, Output<String>> map = new HashMap<>();
                Output<String> key=mykey.privateKey();
                Output<String> bucketName = (assignmentStorage).name();
                map.put("GCP_SERVICE_ACCOUNT_KEY", key);

                map.put("GCP_BUCKET_NAME", bucketName);
                map.put("SG_API_KEY", Output.of(data.get("api_key").toString()));
//                map.put("TEMPLATE_ID", Output.of(map.get("template_id").toString()));
                Output<Map<String, String>> outputEnv = Output.all(map.values()).applyValue(values -> {
                    Map<String, String> mapped = new HashMap<>();
                    int i = 0;
                    for (String k : map.keySet()) {
                        mapped.put(k, values.get(i));
                        i++;
                    }
                    return mapped;
                });


                List<RolePolicyAttachment> resources = new ArrayList<>();
//                resources.add(assignmentStorage);
                resources.add(lambdaLogs);
                resources.add(dynamoDB);
                Function testLambda = new Function("testLambda", FunctionArgs.builder()
                        .code(new FileArchive("C:\\Users\\sani\\Desktop\\lambda_fubction\\serverless_infra\\node_modules (2).zip"))
                        .role(iamForLambda.arn())
                        .handler("index.handler")
                        .runtime("nodejs18.x").timeout(300)
                        .environment(FunctionEnvironmentArgs.builder()
                                .variables(outputEnv)
                                .build()).
                        build());
                Output<String> topicArn = sns_topic.arn();
                Permission permission = new Permission("permission", PermissionArgs.builder()
                        .function(testLambda.name())
                        .action("lambda:InvokeFunction")
                        .principal("sns.amazonaws.com")
                        .sourceArn(topicArn)
                        .build());
                TopicSubscription eventSourceMapping = new TopicSubscription("topic-subscription", TopicSubscriptionArgs.builder()
                        .topic(topicArn)
                        .protocol("lambda")
                        .endpoint(testLambda.arn())
                        .build());

                var internetGateway = new InternetGateway("my-igw",
                        InternetGatewayArgs.builder()
                                .vpcId(vpcBase.id())
                                .tags(Map.of("Name", vpcString+ "_InternetGateWay"))
                                .build());
                var routeTablePr = new RouteTable(vpcString + "_PublicRouteTable",
                        RouteTableArgs.builder()
                                .tags(Map.of("Name", vpcString+ "_PublicRouteTable"))
                                .vpcId(vpcBase.id())
                                .routes(RouteTableRouteArgs.builder().cidrBlock("0.0.0.0/0").gatewayId(internetGateway.id()).build())
                                .build()
                );
                var routeTablePub = new RouteTable(vpcString + "_PrivateRouteTable",
                        RouteTableArgs.builder()
                                .tags(Map.of("Name", vpcString+ "_PrivateRouteTable"))
                                .vpcId(vpcBase.id())
                                .build()
                );
                for(int i =0; i < totalZones; i++){
                    new RouteTableAssociation("PublicRouteTableAssociation_" + i,
                            RouteTableAssociationArgs.builder()
                                    .subnetId(totalPublicSubnets.get(i).id())
                                    .routeTableId(routeTablePr.id())
                                    .build());

                    new RouteTableAssociation("PrivateRouteTableAssociation_" + i,
                            RouteTableAssociationArgs.builder()
                                    .subnetId(privateSubNetList.get(i).id())
                                    .routeTableId(routeTablePub.id())
                                    .build());
                }
                return  null;
            });
            ctx.export("VPC-Id", vpcBase.id());
        });
    }



    public static List<Subnet> createPublicSubnets(int num,String vpcName,Vpc vpc,List<String> list, int noOfZones,List<String> subnetStrings){
        List<Subnet> publicSubNetList = new ArrayList<>();
        int n  = Math.min(num,noOfZones);
        for (int i = 0; i <n ; i++) {
            String subnetName = vpcName + "_PublicSubnet_" +i;
            var publicSubnet = new Subnet(subnetName,
                    SubnetArgs.builder()
                            .cidrBlock(subnetStrings.get(i))
                            .vpcId(vpc.id())
                            .availabilityZone(list.get(i))
                            .mapPublicIpOnLaunch(true)
                            .tags(Map.of("Name",subnetName))
                            .build());
            publicSubNetList.add(publicSubnet);
        }
        return publicSubNetList;
    }
    public static List<Subnet> createPrivateSubnets(int num,String vpcName,Vpc vpc,List<String> list, int noOfZones,List<String> subnetString){
        List<Subnet> privateSubnetList = new ArrayList<>();
        int n  = Math.min(num,noOfZones);
        for (int i = 0; i < n; i++) {
            String subnetName = vpcName + "_PrivateSubnet_" +i;
            var publicSubnet = new Subnet(subnetName,
                    SubnetArgs.builder()
                            .cidrBlock(subnetString.get(i+n))
                            .vpcId(vpc.id())
                            .availabilityZone(list.get(i))
                            .tags(Map.of("Name",subnetName))
                            .build());
            privateSubnetList.add(publicSubnet);
        }
        return privateSubnetList;
    }
    public static List<String> calculateSubnets(String vpcCidr, int numSubnets) {
        try {
            InetAddress vpcAddress = Inet4Address.getByName(vpcCidr.split("/")[0]);
            int vpcCidrPrefixLength = Integer.parseInt(vpcCidr.split("/")[1]);
            double ceil = Math.ceil(Math.log(numSubnets) / Math.log(2));
            int subnetPrefixLength = vpcCidrPrefixLength + (int) ceil;
            int availableAddresses = 32 - subnetPrefixLength;

            List<String> subnets = new ArrayList<>();
            for (int i = 0; i < numSubnets; i++) {
                int subnetSize = (int) Math.pow(2, availableAddresses);
                String subnetCidr = vpcAddress.getHostAddress() + "/" + subnetPrefixLength;
                subnets.add(subnetCidr);
                vpcAddress = InetAddress.getByName(advanceIPAddress(vpcAddress.getHostAddress(), subnetSize));
            }

            return subnets;
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return null;
    }
    public static String advanceIPAddress(String ipAddress, int offset) throws UnknownHostException {
        InetAddress addr = Inet4Address.getByName(ipAddress);
        byte[] bytes = addr.getAddress();
        int value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xff);
        }
        value += offset;

        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) ((value >> (24 - i * 8)) & 0xff);
        }
        return InetAddress.getByAddress(bytes).getHostAddress();
    }

    public static Output <String> securityGroup(Vpc vpc, String vpcName, Map<String, Object> data, Output<String> loadBalancer_securityGroupId) {
        String security_group = "Ec2_SecurityGroup"+vpcName;
        List<Double> ports = (List<Double>) data.get("ports");
        String publicIpString = data.get("public-cidr").toString();
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        for (Double port : ports) {
            securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                    .fromPort(port.intValue())
                    .toPort(port.intValue())
                    .protocol("tcp").
                    securityGroups((loadBalancer_securityGroupId.applyValue(Collections::singletonList)))
                    .cidrBlocks(publicIpString).build());}

        var outBoundLogs = new SecurityGroupRule("ec2-lb-outbound-logs",
                SecurityGroupRuleArgs.builder()
                        .description("Allow TCP connections")
                        .type("egress")
                        .fromPort(0)
                        .toPort(0)
                        .protocol("-1")
                        .cidrBlocks("0.0.0.0/0")
                        .securityGroupId(loadBalancer_securityGroupId)
                        .build());
        var allowTcp = new SecurityGroup(security_group,
                SecurityGroupArgs.builder()
                        .description("ALlow TCP connections")
                        .vpcId(vpc.id()).ingress(securityGroupIngressArgsList)
                        .tags(Map.of("Name", security_group))
                        .build());

        return allowTcp.id();
    }
    private static Output<String> LB_securityGroup(Vpc vpcBase, String myVpc, Map data) {
        String security_group = "load_balancer_SecurityGroup";
        List<Double> ports = (List<Double>) data.get("LB_ports");
        String publicIpString = data.get("public-cidr").toString();
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        for (Double port : ports) {
            securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                    .fromPort(port.intValue())
                    .toPort(port.intValue())
                    .protocol("tcp")
                    .cidrBlocks(publicIpString).build());}
        var securityGroup = new SecurityGroup(security_group,
                SecurityGroupArgs.builder()
                        .description("LB_Ports")
                        .vpcId(vpcBase.id()).ingress(securityGroupIngressArgsList)
                        .tags(Map.of("Name", security_group))
                        .build());
//        var allowAll = new SecurityGroupRule("allowAll", SecurityGroupRuleArgs.builder()
//                .type("egress")
//                .toPort(0)
//                .protocol("-1")
//                .fromPort(0)
//                .securityGroupId(securityGroup.id())
//                .build());
        return securityGroup.id();

    }
    private static LaunchTemplate launch_template(Output<String> securityGroupId, Output<String> userDATA, InstanceProfile instanceprofile, Map data, Subnet firstSubnet) {
        Output<String> encodedUserData = userDATA.applyValue(s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
        var launchTemplate = new LaunchTemplate("new_template", LaunchTemplateArgs.builder()
                .iamInstanceProfile(LaunchTemplateIamInstanceProfileArgs.builder().name("iam")
                        .name((instanceprofile.id()))
                        .build())
                .networkInterfaces(LaunchTemplateNetworkInterfaceArgs.builder().securityGroups(securityGroupId.applyValue(s-> singletonList(s)))
                        .associatePublicIpAddress(String.valueOf(true))
                        .build())
                .placement(LaunchTemplatePlacementArgs.builder()
                        .availabilityZone(firstSubnet.availabilityZone())
                        .build())
                .instanceType((String) data.get("instancetype"))
                .blockDeviceMappings(LaunchTemplateBlockDeviceMappingArgs.builder()
                        .deviceName("/dev/sdf")
                        .ebs(LaunchTemplateBlockDeviceMappingEbsArgs.builder()
                                .volumeSize(20)
                                .build())
                        .build())
                .keyName((String) data.get("keyname")) // Ensure this value is not null
                .userData(encodedUserData)
                .imageId((String) data.get("ami_id"))
                .build());
        return launchTemplate;

    }



//    public static Instance instanceEc2(String newvpc, Output<String> securityGroupId, Subnet subnet1, Map<String, Object> data, Output<String> userdata,InstanceProfile profile) {
//        String enTwo = "Ec2Instance";
//        Double size = (Double) data.get("volume");
//        InstanceEbsBlockDeviceArgs ebcBlock = InstanceEbsBlockDeviceArgs.builder().deviceName("/dev/xvda").volumeType("gp2").volumeSize(size.intValue()).deleteOnTermination(true).build();
//        String ami_id = (String) data.get("ami_id");
//        List<InstanceEbsBlockDeviceArgs> ebslist = new ArrayList<>();
//        ebslist.add(ebcBlock);
//        InstanceArgs.Builder instanArgs = InstanceArgs.builder();
//        instanArgs.ami(ami_id);
//        return new Instance(enTwo, instanArgs.instanceType(data.get("instancetype").toString()).iamInstanceProfile(profile.id()).
//                ebsBlockDevices(ebslist).subnetId(subnet1.id()).keyName(data.get("keyname").toString()).associatePublicIpAddress(true).
//                userData(userdata).vpcSecurityGroupIds(securityGroupId.applyValue(Collections::singletonList)).disableApiTermination(false).tags(Map.of("Name", enTwo)).build());
//
//    }

    public static com.pulumi.aws.rds.Instance instanceDB(Vpc vpc, String newvpc, Output<String> securityGroupId, List<Subnet> privatesubnet, ParameterGroup pg, Map<String, Object> data) {

        SubnetGroup subnetGroup = new SubnetGroup("defaultsubnets", SubnetGroupArgs.builder()
                .subnetIds(Output.all(privatesubnet.stream().map(Subnet::id).collect(toList())))
                .name("subnetgroup")
                .build());

        String instancetype="db.t2.micro";
        int storage=20;
        String dbuser="root";
        String dbpassword="root123!#$";
        String dbname="assignment3";
        String security_group_DB = "Database_SecurityGroup";
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                .fromPort(3307)
                .toPort(3307)
                .protocol("tcp")
                .securityGroups(securityGroupId.applyValue(Collections::singletonList)).build());

        SecurityGroup securityGroupDb = new SecurityGroup(security_group_DB,
                SecurityGroupArgs.builder()
                        .description("Allow 3307 port and decline others")
                        .vpcId(vpc.id()).ingress(securityGroupIngressArgsList)
                        .tags(Map.of("Name", security_group_DB))
                        .build());

        var outBound = new SecurityGroupRule("ec2-rds-outbound",
                SecurityGroupRuleArgs.builder()
                        .description("Allow TCP connections")
                        .type("egress")
                        .fromPort(3307)
                        .toPort(3307)
                        .protocol("tcp")
                        .sourceSecurityGroupId(securityGroupDb.id())
                        .securityGroupId(securityGroupId)
                        .build());
        var outBoundLog = new SecurityGroupRule("ec2-rds-outbound-logs",
                SecurityGroupRuleArgs.builder()
                        .description("Allow TCP connections")
                        .type("egress")
                        .fromPort(443)
                        .toPort(443)
                        .protocol("tcp")
                        .cidrBlocks("0.0.0.0/0")
                        .securityGroupId(securityGroupId)
                        .build());
        com.pulumi.aws.rds.Instance rdsInstance = new com.pulumi.aws.rds.Instance("RDSInstance", com.pulumi.aws.rds.InstanceArgs.builder()
                .instanceClass(instancetype)
                .allocatedStorage(storage)
                .engine("mariadb")
                .engineVersion("10.4")
                .identifier("csye6225")
                .username(dbuser)
                .password(dbpassword)
                .skipFinalSnapshot(true)
                .publiclyAccessible(false)
                .multiAz(false)
                .parameterGroupName(pg.name())
                .dbName(dbname)
                .port(3307)
                .vpcSecurityGroupIds(securityGroupDb.id().applyValue(List::of))
                .dbSubnetGroupName(subnetGroup.name())
                .tags(Map.of("Name","myRDSInstance"))
                .build());
        return rdsInstance;
    }
//    private static String base64Encode(Output<String> input) {
//        return (input.applyValue(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
//
////        String s= input. applyValue(v -> String.format("input", v)).toString();
////        byte[] encodedBytes = Base64.getEncoder().encode(s.getBytes(StandardCharsets.UTF_8));
////        return new String(encodedBytes, StandardCharsets.UTF_8);
//
//    }
    private static Output<String> userdataGenerater(com.pulumi.aws.rds.Instance rdsInstance) {
        Output<String> instanceEndPoint = rdsInstance.address();
        String dbuser = "root";
        String dbpassword = "root123!#$";
        String dbname = "assignment3";
        double dbport = 3307;
        return instanceEndPoint.applyValue(
                endPointResult -> String.format(
                            "#!/bin/bash\n" +
                                    "{\n" +
                                    "echo \"DB_HOST=%s\"\n" +
                                    "echo \"DB_PORT=%d\"\n" +
                                    "echo \"DB_USER=%s\"\n" +
                                    "echo \"DB_PASSWORD=%s\"\n" +
                                    "echo \"DB_NAME=%s\"\n" +
                                    "} >> /opt/csye6225/application.properties\n" +
                                    "chmod 750 /opt/csye6225/application.properties\n"+
                                    "chmod 750 /opt/csye6225/cloudwatch-config.json\n"+
                                    "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s -c file:/var/log/cloudwatch.json\n"+
                                    "sudo systemctl restart amazon-cloudwatch-agent.service\n",

                            endPointResult, (int)dbport, dbuser, dbpassword, dbname)


        );

    }
}




