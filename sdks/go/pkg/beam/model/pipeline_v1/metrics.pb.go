// Code generated by protoc-gen-go. DO NOT EDIT.
// source: metrics.proto

package pipeline_v1

import (
	fmt "fmt"
	proto "github.com/golang/protobuf/proto"
	descriptor "github.com/golang/protobuf/protoc-gen-go/descriptor"
	_ "github.com/golang/protobuf/ptypes/timestamp"
	math "math"
)

// Reference imports to suppress errors if they are not otherwise used.
var _ = proto.Marshal
var _ = fmt.Errorf
var _ = math.Inf

// This is a compile-time assertion to ensure that this generated file
// is compatible with the proto package it is being compiled against.
// A compilation error at this line likely means your copy of the
// proto package needs to be updated.
const _ = proto.ProtoPackageIsVersion3 // please upgrade the proto package

type MonitoringInfoSpecs_Enum int32

const (
	// Represents an integer counter where values are summed across bundles.
	MonitoringInfoSpecs_USER_SUM_INT64 MonitoringInfoSpecs_Enum = 0
	// Represents a double counter where values are summed across bundles.
	MonitoringInfoSpecs_USER_SUM_DOUBLE MonitoringInfoSpecs_Enum = 1
	// Represents a distribution of an integer value where:
	//   - count: represents the number of values seen across all bundles
	//   - sum: represents the total of the value across all bundles
	//   - min: represents the smallest value seen across all bundles
	//   - max: represents the largest value seen across all bundles
	MonitoringInfoSpecs_USER_DISTRIBUTION_INT64 MonitoringInfoSpecs_Enum = 2
	// Represents a distribution of a double value where:
	//   - count: represents the number of values seen across all bundles
	//   - sum: represents the total of the value across all bundles
	//   - min: represents the smallest value seen across all bundles
	//   - max: represents the largest value seen across all bundles
	MonitoringInfoSpecs_USER_DISTRIBUTION_DOUBLE MonitoringInfoSpecs_Enum = 3
	// Represents the latest seen integer value. The timestamp is used to
	// provide an "ordering" over multiple values to determine which is the
	// latest.
	MonitoringInfoSpecs_USER_LATEST_INT64 MonitoringInfoSpecs_Enum = 4
	// Represents the latest seen double value. The timestamp is used to
	// provide an "ordering" over multiple values to determine which is the
	// latest.
	MonitoringInfoSpecs_USER_LATEST_DOUBLE MonitoringInfoSpecs_Enum = 5
	// Represents the largest set of integer values seen across bundles.
	MonitoringInfoSpecs_USER_TOP_N_INT64 MonitoringInfoSpecs_Enum = 6
	// Represents the largest set of double values seen across bundles.
	MonitoringInfoSpecs_USER_TOP_N_DOUBLE MonitoringInfoSpecs_Enum = 7
	// Represents the smallest set of integer values seen across bundles.
	MonitoringInfoSpecs_USER_BOTTOM_N_INT64 MonitoringInfoSpecs_Enum = 8
	// Represents the smallest set of double values seen across bundles.
	MonitoringInfoSpecs_USER_BOTTOM_N_DOUBLE MonitoringInfoSpecs_Enum = 9
	MonitoringInfoSpecs_ELEMENT_COUNT        MonitoringInfoSpecs_Enum = 10
	MonitoringInfoSpecs_SAMPLED_BYTE_SIZE    MonitoringInfoSpecs_Enum = 11
	MonitoringInfoSpecs_START_BUNDLE_MSECS   MonitoringInfoSpecs_Enum = 12
	MonitoringInfoSpecs_PROCESS_BUNDLE_MSECS MonitoringInfoSpecs_Enum = 13
	MonitoringInfoSpecs_FINISH_BUNDLE_MSECS  MonitoringInfoSpecs_Enum = 14
	MonitoringInfoSpecs_TOTAL_MSECS          MonitoringInfoSpecs_Enum = 15
	// All values reported across all beam:metric:ptransform_progress:.*:v1
	// metrics are of the same magnitude.
	MonitoringInfoSpecs_WORK_REMAINING MonitoringInfoSpecs_Enum = 16
	// All values reported across all beam:metric:ptransform_progress:.*:v1
	// metrics are of the same magnitude.
	MonitoringInfoSpecs_WORK_COMPLETED MonitoringInfoSpecs_Enum = 17
	// The (0-based) index of the latest item processed from the data channel.
	// This gives an indication of the SDKs progress through the data channel,
	// and is a lower bound on where it is able to split.
	// For an SDK that processes items sequentially, this is equivalently the
	// number of items fully processed (or -1 if processing has not yet started).
	MonitoringInfoSpecs_DATA_CHANNEL_READ_INDEX MonitoringInfoSpecs_Enum = 18
)

var MonitoringInfoSpecs_Enum_name = map[int32]string{
	0:  "USER_SUM_INT64",
	1:  "USER_SUM_DOUBLE",
	2:  "USER_DISTRIBUTION_INT64",
	3:  "USER_DISTRIBUTION_DOUBLE",
	4:  "USER_LATEST_INT64",
	5:  "USER_LATEST_DOUBLE",
	6:  "USER_TOP_N_INT64",
	7:  "USER_TOP_N_DOUBLE",
	8:  "USER_BOTTOM_N_INT64",
	9:  "USER_BOTTOM_N_DOUBLE",
	10: "ELEMENT_COUNT",
	11: "SAMPLED_BYTE_SIZE",
	12: "START_BUNDLE_MSECS",
	13: "PROCESS_BUNDLE_MSECS",
	14: "FINISH_BUNDLE_MSECS",
	15: "TOTAL_MSECS",
	16: "WORK_REMAINING",
	17: "WORK_COMPLETED",
	18: "DATA_CHANNEL_READ_INDEX",
}

var MonitoringInfoSpecs_Enum_value = map[string]int32{
	"USER_SUM_INT64":           0,
	"USER_SUM_DOUBLE":          1,
	"USER_DISTRIBUTION_INT64":  2,
	"USER_DISTRIBUTION_DOUBLE": 3,
	"USER_LATEST_INT64":        4,
	"USER_LATEST_DOUBLE":       5,
	"USER_TOP_N_INT64":         6,
	"USER_TOP_N_DOUBLE":        7,
	"USER_BOTTOM_N_INT64":      8,
	"USER_BOTTOM_N_DOUBLE":     9,
	"ELEMENT_COUNT":            10,
	"SAMPLED_BYTE_SIZE":        11,
	"START_BUNDLE_MSECS":       12,
	"PROCESS_BUNDLE_MSECS":     13,
	"FINISH_BUNDLE_MSECS":      14,
	"TOTAL_MSECS":              15,
	"WORK_REMAINING":           16,
	"WORK_COMPLETED":           17,
	"DATA_CHANNEL_READ_INDEX":  18,
}

func (x MonitoringInfoSpecs_Enum) String() string {
	return proto.EnumName(MonitoringInfoSpecs_Enum_name, int32(x))
}

func (MonitoringInfoSpecs_Enum) EnumDescriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{2, 0}
}

type MonitoringInfo_MonitoringInfoLabels int32

const (
	// The values used for TRANSFORM, PCOLLECTION, WINDOWING_STRATEGY
	// CODER, ENVIRONMENT, etc. must always match the keys used to
	// refer to them. For actively processed bundles, these should match the
	// values within the ProcessBundleDescriptor. For job management APIs,
	// these should match values within the original pipeline representation.
	MonitoringInfo_TRANSFORM          MonitoringInfo_MonitoringInfoLabels = 0
	MonitoringInfo_PCOLLECTION        MonitoringInfo_MonitoringInfoLabels = 1
	MonitoringInfo_WINDOWING_STRATEGY MonitoringInfo_MonitoringInfoLabels = 2
	MonitoringInfo_CODER              MonitoringInfo_MonitoringInfoLabels = 3
	MonitoringInfo_ENVIRONMENT        MonitoringInfo_MonitoringInfoLabels = 4
	MonitoringInfo_NAMESPACE          MonitoringInfo_MonitoringInfoLabels = 5
	MonitoringInfo_NAME               MonitoringInfo_MonitoringInfoLabels = 6
)

var MonitoringInfo_MonitoringInfoLabels_name = map[int32]string{
	0: "TRANSFORM",
	1: "PCOLLECTION",
	2: "WINDOWING_STRATEGY",
	3: "CODER",
	4: "ENVIRONMENT",
	5: "NAMESPACE",
	6: "NAME",
}

var MonitoringInfo_MonitoringInfoLabels_value = map[string]int32{
	"TRANSFORM":          0,
	"PCOLLECTION":        1,
	"WINDOWING_STRATEGY": 2,
	"CODER":              3,
	"ENVIRONMENT":        4,
	"NAMESPACE":          5,
	"NAME":               6,
}

func (x MonitoringInfo_MonitoringInfoLabels) String() string {
	return proto.EnumName(MonitoringInfo_MonitoringInfoLabels_name, int32(x))
}

func (MonitoringInfo_MonitoringInfoLabels) EnumDescriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{4, 0}
}

type MonitoringInfoTypeUrns_Enum int32

const (
	// Represents an integer counter where values are summed across bundles.
	//
	// Encoding: <value>
	//   - value: beam:coder:varint:v1
	MonitoringInfoTypeUrns_SUM_INT64_TYPE MonitoringInfoTypeUrns_Enum = 0
	// Represents a double counter where values are summed across bundles.
	//
	// Encoding: <value>
	//   value: beam:coder:double:v1
	MonitoringInfoTypeUrns_SUM_DOUBLE_TYPE MonitoringInfoTypeUrns_Enum = 1
	// Represents a distribution of an integer value where:
	//   - count: represents the number of values seen across all bundles
	//   - sum: represents the total of the value across all bundles
	//   - min: represents the smallest value seen across all bundles
	//   - max: represents the largest value seen across all bundles
	//
	// Encoding: <count><sum><min><max>
	//   - count: beam:coder:varint:v1
	//   - sum:   beam:coder:varint:v1
	//   - min:   beam:coder:varint:v1
	//   - max:   beam:coder:varint:v1
	MonitoringInfoTypeUrns_DISTRIBUTION_INT64_TYPE MonitoringInfoTypeUrns_Enum = 2
	// Represents a distribution of a double value where:
	//   - count: represents the number of values seen across all bundles
	//   - sum: represents the total of the value across all bundles
	//   - min: represents the smallest value seen across all bundles
	//   - max: represents the largest value seen across all bundles
	//
	// Encoding: <count><sum><min><max>
	//   - count: beam:coder:varint:v1
	//   - sum:   beam:coder:double:v1
	//   - min:   beam:coder:double:v1
	//   - max:   beam:coder:double:v1
	MonitoringInfoTypeUrns_DISTRIBUTION_DOUBLE_TYPE MonitoringInfoTypeUrns_Enum = 3
	// Represents the latest seen integer value. The timestamp is used to
	// provide an "ordering" over multiple values to determine which is the
	// latest.
	//
	// Encoding: <timestamp><value>
	//   - timestamp: beam:coder:varint:v1     (milliseconds since epoch)
	//   - value:     beam:coder:varint:v1
	MonitoringInfoTypeUrns_LATEST_INT64_TYPE MonitoringInfoTypeUrns_Enum = 4
	// Represents the latest seen double value. The timestamp is used to
	// provide an "ordering" over multiple values to determine which is the
	// latest.
	//
	// Encoding: <timestamp><value>
	//   - timestamp: beam:coder:varint:v1     (milliseconds since epoch)
	//   - value:     beam:coder:double:v1
	MonitoringInfoTypeUrns_LATEST_DOUBLE_TYPE MonitoringInfoTypeUrns_Enum = 5
	// Represents the largest set of integer values seen across bundles.
	//
	// Encoding: <iter><value1><value2>...<valueN></iter>
	//   - iter:   beam:coder:iterable:v1
	//   - valueX: beam:coder:varint:v1
	MonitoringInfoTypeUrns_TOP_N_INT64_TYPE MonitoringInfoTypeUrns_Enum = 6
	// Represents the largest set of double values seen across bundles.
	//
	// Encoding: <iter><value1><value2>...<valueN></iter>
	//   - iter:   beam:coder:iterable:v1
	//   - valueX: beam:coder<beam:coder:double:v1
	MonitoringInfoTypeUrns_TOP_N_DOUBLE_TYPE MonitoringInfoTypeUrns_Enum = 7
	// Represents the smallest set of integer values seen across bundles.
	//
	// Encoding: <iter><value1><value2>...<valueN></iter>
	//   - iter:   beam:coder:iterable:v1
	//   - valueX: beam:coder:varint:v1
	MonitoringInfoTypeUrns_BOTTOM_N_INT64_TYPE MonitoringInfoTypeUrns_Enum = 8
	// Represents the smallest set of double values seen across bundles.
	//
	// Encoding: <iter><value1><value2>...<valueN></iter>
	//   - iter:   beam:coder:iterable:v1
	//   - valueX: beam:coder:double:v1
	MonitoringInfoTypeUrns_BOTTOM_N_DOUBLE_TYPE MonitoringInfoTypeUrns_Enum = 9
	// Encoding: <iter><value1><value2>...<valueN></iter>
	//   - iter:   beam:coder:iterable:v1
	//   - valueX: beam:coder:double:v1
	MonitoringInfoTypeUrns_PROGRESS_TYPE MonitoringInfoTypeUrns_Enum = 10
)

var MonitoringInfoTypeUrns_Enum_name = map[int32]string{
	0:  "SUM_INT64_TYPE",
	1:  "SUM_DOUBLE_TYPE",
	2:  "DISTRIBUTION_INT64_TYPE",
	3:  "DISTRIBUTION_DOUBLE_TYPE",
	4:  "LATEST_INT64_TYPE",
	5:  "LATEST_DOUBLE_TYPE",
	6:  "TOP_N_INT64_TYPE",
	7:  "TOP_N_DOUBLE_TYPE",
	8:  "BOTTOM_N_INT64_TYPE",
	9:  "BOTTOM_N_DOUBLE_TYPE",
	10: "PROGRESS_TYPE",
}

var MonitoringInfoTypeUrns_Enum_value = map[string]int32{
	"SUM_INT64_TYPE":           0,
	"SUM_DOUBLE_TYPE":          1,
	"DISTRIBUTION_INT64_TYPE":  2,
	"DISTRIBUTION_DOUBLE_TYPE": 3,
	"LATEST_INT64_TYPE":        4,
	"LATEST_DOUBLE_TYPE":       5,
	"TOP_N_INT64_TYPE":         6,
	"TOP_N_DOUBLE_TYPE":        7,
	"BOTTOM_N_INT64_TYPE":      8,
	"BOTTOM_N_DOUBLE_TYPE":     9,
	"PROGRESS_TYPE":            10,
}

func (x MonitoringInfoTypeUrns_Enum) String() string {
	return proto.EnumName(MonitoringInfoTypeUrns_Enum_name, int32(x))
}

func (MonitoringInfoTypeUrns_Enum) EnumDescriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{5, 0}
}

// A specification for describing a well known MonitoringInfo.
//
// All specifications are uniquely identified by the urn.
type MonitoringInfoSpec struct {
	// Defines the semantic meaning of the metric or monitored state.
	//
	// See MonitoringInfoSpecs.Enum for the set of well known metrics/monitored
	// state.
	Urn string `protobuf:"bytes,1,opt,name=urn,proto3" json:"urn,omitempty"`
	// Defines the required encoding and aggregation method for the payload.
	//
	// See MonitoringInfoTypeUrns.Enum for the set of well known types.
	Type string `protobuf:"bytes,2,opt,name=type,proto3" json:"type,omitempty"`
	// The list of required labels for the specified urn and type.
	RequiredLabels []string `protobuf:"bytes,3,rep,name=required_labels,json=requiredLabels,proto3" json:"required_labels,omitempty"`
	// Extra non functional parts of the spec for descriptive purposes.
	// i.e. description, units, etc.
	Annotations          []*Annotation `protobuf:"bytes,4,rep,name=annotations,proto3" json:"annotations,omitempty"`
	XXX_NoUnkeyedLiteral struct{}      `json:"-"`
	XXX_unrecognized     []byte        `json:"-"`
	XXX_sizecache        int32         `json:"-"`
}

func (m *MonitoringInfoSpec) Reset()         { *m = MonitoringInfoSpec{} }
func (m *MonitoringInfoSpec) String() string { return proto.CompactTextString(m) }
func (*MonitoringInfoSpec) ProtoMessage()    {}
func (*MonitoringInfoSpec) Descriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{0}
}

func (m *MonitoringInfoSpec) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_MonitoringInfoSpec.Unmarshal(m, b)
}
func (m *MonitoringInfoSpec) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_MonitoringInfoSpec.Marshal(b, m, deterministic)
}
func (m *MonitoringInfoSpec) XXX_Merge(src proto.Message) {
	xxx_messageInfo_MonitoringInfoSpec.Merge(m, src)
}
func (m *MonitoringInfoSpec) XXX_Size() int {
	return xxx_messageInfo_MonitoringInfoSpec.Size(m)
}
func (m *MonitoringInfoSpec) XXX_DiscardUnknown() {
	xxx_messageInfo_MonitoringInfoSpec.DiscardUnknown(m)
}

var xxx_messageInfo_MonitoringInfoSpec proto.InternalMessageInfo

func (m *MonitoringInfoSpec) GetUrn() string {
	if m != nil {
		return m.Urn
	}
	return ""
}

func (m *MonitoringInfoSpec) GetType() string {
	if m != nil {
		return m.Type
	}
	return ""
}

func (m *MonitoringInfoSpec) GetRequiredLabels() []string {
	if m != nil {
		return m.RequiredLabels
	}
	return nil
}

func (m *MonitoringInfoSpec) GetAnnotations() []*Annotation {
	if m != nil {
		return m.Annotations
	}
	return nil
}

// The key name and value string of MonitoringInfo annotations.
type Annotation struct {
	Key                  string   `protobuf:"bytes,1,opt,name=key,proto3" json:"key,omitempty"`
	Value                string   `protobuf:"bytes,2,opt,name=value,proto3" json:"value,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *Annotation) Reset()         { *m = Annotation{} }
func (m *Annotation) String() string { return proto.CompactTextString(m) }
func (*Annotation) ProtoMessage()    {}
func (*Annotation) Descriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{1}
}

func (m *Annotation) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_Annotation.Unmarshal(m, b)
}
func (m *Annotation) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_Annotation.Marshal(b, m, deterministic)
}
func (m *Annotation) XXX_Merge(src proto.Message) {
	xxx_messageInfo_Annotation.Merge(m, src)
}
func (m *Annotation) XXX_Size() int {
	return xxx_messageInfo_Annotation.Size(m)
}
func (m *Annotation) XXX_DiscardUnknown() {
	xxx_messageInfo_Annotation.DiscardUnknown(m)
}

var xxx_messageInfo_Annotation proto.InternalMessageInfo

func (m *Annotation) GetKey() string {
	if m != nil {
		return m.Key
	}
	return ""
}

func (m *Annotation) GetValue() string {
	if m != nil {
		return m.Value
	}
	return ""
}

// A set of well known MonitoringInfo specifications.
type MonitoringInfoSpecs struct {
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *MonitoringInfoSpecs) Reset()         { *m = MonitoringInfoSpecs{} }
func (m *MonitoringInfoSpecs) String() string { return proto.CompactTextString(m) }
func (*MonitoringInfoSpecs) ProtoMessage()    {}
func (*MonitoringInfoSpecs) Descriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{2}
}

func (m *MonitoringInfoSpecs) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_MonitoringInfoSpecs.Unmarshal(m, b)
}
func (m *MonitoringInfoSpecs) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_MonitoringInfoSpecs.Marshal(b, m, deterministic)
}
func (m *MonitoringInfoSpecs) XXX_Merge(src proto.Message) {
	xxx_messageInfo_MonitoringInfoSpecs.Merge(m, src)
}
func (m *MonitoringInfoSpecs) XXX_Size() int {
	return xxx_messageInfo_MonitoringInfoSpecs.Size(m)
}
func (m *MonitoringInfoSpecs) XXX_DiscardUnknown() {
	xxx_messageInfo_MonitoringInfoSpecs.DiscardUnknown(m)
}

var xxx_messageInfo_MonitoringInfoSpecs proto.InternalMessageInfo

// A set of properties for the MonitoringInfoLabel, this is useful to obtain
// the proper label string for the MonitoringInfoLabel.
type MonitoringInfoLabelProps struct {
	// The label key to use in the MonitoringInfo labels map.
	Name                 string   `protobuf:"bytes,1,opt,name=name,proto3" json:"name,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *MonitoringInfoLabelProps) Reset()         { *m = MonitoringInfoLabelProps{} }
func (m *MonitoringInfoLabelProps) String() string { return proto.CompactTextString(m) }
func (*MonitoringInfoLabelProps) ProtoMessage()    {}
func (*MonitoringInfoLabelProps) Descriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{3}
}

func (m *MonitoringInfoLabelProps) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_MonitoringInfoLabelProps.Unmarshal(m, b)
}
func (m *MonitoringInfoLabelProps) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_MonitoringInfoLabelProps.Marshal(b, m, deterministic)
}
func (m *MonitoringInfoLabelProps) XXX_Merge(src proto.Message) {
	xxx_messageInfo_MonitoringInfoLabelProps.Merge(m, src)
}
func (m *MonitoringInfoLabelProps) XXX_Size() int {
	return xxx_messageInfo_MonitoringInfoLabelProps.Size(m)
}
func (m *MonitoringInfoLabelProps) XXX_DiscardUnknown() {
	xxx_messageInfo_MonitoringInfoLabelProps.DiscardUnknown(m)
}

var xxx_messageInfo_MonitoringInfoLabelProps proto.InternalMessageInfo

func (m *MonitoringInfoLabelProps) GetName() string {
	if m != nil {
		return m.Name
	}
	return ""
}

type MonitoringInfo struct {
	// (Required) Defines the semantic meaning of the metric or monitored state.
	//
	// See MonitoringInfoSpecs.Enum for the set of well known metrics/monitored
	// state.
	Urn string `protobuf:"bytes,1,opt,name=urn,proto3" json:"urn,omitempty"`
	// (Required) Defines the encoding and aggregation method for the payload.
	//
	// See MonitoringInfoTypeUrns.Enum for the set of well known types.
	Type string `protobuf:"bytes,2,opt,name=type,proto3" json:"type,omitempty"`
	// (Required) The metric or monitored state encoded as per the specification
	// defined by the type.
	Payload []byte `protobuf:"bytes,3,opt,name=payload,proto3" json:"payload,omitempty"`
	// A set of key and value labels which define the scope of the metric. For
	// well known URNs, the set of required labels is provided by the associated
	// MonitoringInfoSpec.
	//
	// Either a well defined entity id for matching the enum names in
	// the MonitoringInfoLabels enum or any arbitrary label
	// set by a custom metric or user metric.
	//
	// A monitoring system is expected to be able to aggregate the metrics
	// together for all updates having the same URN and labels. Some systems such
	// as Stackdriver will be able to aggregate the metrics using a subset of the
	// provided labels
	Labels               map[string]string `protobuf:"bytes,4,rep,name=labels,proto3" json:"labels,omitempty" protobuf_key:"bytes,1,opt,name=key,proto3" protobuf_val:"bytes,2,opt,name=value,proto3"`
	XXX_NoUnkeyedLiteral struct{}          `json:"-"`
	XXX_unrecognized     []byte            `json:"-"`
	XXX_sizecache        int32             `json:"-"`
}

func (m *MonitoringInfo) Reset()         { *m = MonitoringInfo{} }
func (m *MonitoringInfo) String() string { return proto.CompactTextString(m) }
func (*MonitoringInfo) ProtoMessage()    {}
func (*MonitoringInfo) Descriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{4}
}

func (m *MonitoringInfo) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_MonitoringInfo.Unmarshal(m, b)
}
func (m *MonitoringInfo) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_MonitoringInfo.Marshal(b, m, deterministic)
}
func (m *MonitoringInfo) XXX_Merge(src proto.Message) {
	xxx_messageInfo_MonitoringInfo.Merge(m, src)
}
func (m *MonitoringInfo) XXX_Size() int {
	return xxx_messageInfo_MonitoringInfo.Size(m)
}
func (m *MonitoringInfo) XXX_DiscardUnknown() {
	xxx_messageInfo_MonitoringInfo.DiscardUnknown(m)
}

var xxx_messageInfo_MonitoringInfo proto.InternalMessageInfo

func (m *MonitoringInfo) GetUrn() string {
	if m != nil {
		return m.Urn
	}
	return ""
}

func (m *MonitoringInfo) GetType() string {
	if m != nil {
		return m.Type
	}
	return ""
}

func (m *MonitoringInfo) GetPayload() []byte {
	if m != nil {
		return m.Payload
	}
	return nil
}

func (m *MonitoringInfo) GetLabels() map[string]string {
	if m != nil {
		return m.Labels
	}
	return nil
}

// A set of well known URNs that specify the encoding and aggregation method.
type MonitoringInfoTypeUrns struct {
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *MonitoringInfoTypeUrns) Reset()         { *m = MonitoringInfoTypeUrns{} }
func (m *MonitoringInfoTypeUrns) String() string { return proto.CompactTextString(m) }
func (*MonitoringInfoTypeUrns) ProtoMessage()    {}
func (*MonitoringInfoTypeUrns) Descriptor() ([]byte, []int) {
	return fileDescriptor_6039342a2ba47b72, []int{5}
}

func (m *MonitoringInfoTypeUrns) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_MonitoringInfoTypeUrns.Unmarshal(m, b)
}
func (m *MonitoringInfoTypeUrns) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_MonitoringInfoTypeUrns.Marshal(b, m, deterministic)
}
func (m *MonitoringInfoTypeUrns) XXX_Merge(src proto.Message) {
	xxx_messageInfo_MonitoringInfoTypeUrns.Merge(m, src)
}
func (m *MonitoringInfoTypeUrns) XXX_Size() int {
	return xxx_messageInfo_MonitoringInfoTypeUrns.Size(m)
}
func (m *MonitoringInfoTypeUrns) XXX_DiscardUnknown() {
	xxx_messageInfo_MonitoringInfoTypeUrns.DiscardUnknown(m)
}

var xxx_messageInfo_MonitoringInfoTypeUrns proto.InternalMessageInfo

var E_LabelProps = &proto.ExtensionDesc{
	ExtendedType:  (*descriptor.EnumValueOptions)(nil),
	ExtensionType: (*MonitoringInfoLabelProps)(nil),
	Field:         127337796,
	Name:          "org.apache.beam.model.pipeline.v1.label_props",
	Tag:           "bytes,127337796,opt,name=label_props",
	Filename:      "metrics.proto",
}

var E_MonitoringInfoSpec = &proto.ExtensionDesc{
	ExtendedType:  (*descriptor.EnumValueOptions)(nil),
	ExtensionType: (*MonitoringInfoSpec)(nil),
	Field:         207174266,
	Name:          "org.apache.beam.model.pipeline.v1.monitoring_info_spec",
	Tag:           "bytes,207174266,opt,name=monitoring_info_spec",
	Filename:      "metrics.proto",
}

func init() {
	proto.RegisterEnum("org.apache.beam.model.pipeline.v1.MonitoringInfoSpecs_Enum", MonitoringInfoSpecs_Enum_name, MonitoringInfoSpecs_Enum_value)
	proto.RegisterEnum("org.apache.beam.model.pipeline.v1.MonitoringInfo_MonitoringInfoLabels", MonitoringInfo_MonitoringInfoLabels_name, MonitoringInfo_MonitoringInfoLabels_value)
	proto.RegisterEnum("org.apache.beam.model.pipeline.v1.MonitoringInfoTypeUrns_Enum", MonitoringInfoTypeUrns_Enum_name, MonitoringInfoTypeUrns_Enum_value)
	proto.RegisterType((*MonitoringInfoSpec)(nil), "org.apache.beam.model.pipeline.v1.MonitoringInfoSpec")
	proto.RegisterType((*Annotation)(nil), "org.apache.beam.model.pipeline.v1.Annotation")
	proto.RegisterType((*MonitoringInfoSpecs)(nil), "org.apache.beam.model.pipeline.v1.MonitoringInfoSpecs")
	proto.RegisterType((*MonitoringInfoLabelProps)(nil), "org.apache.beam.model.pipeline.v1.MonitoringInfoLabelProps")
	proto.RegisterType((*MonitoringInfo)(nil), "org.apache.beam.model.pipeline.v1.MonitoringInfo")
	proto.RegisterMapType((map[string]string)(nil), "org.apache.beam.model.pipeline.v1.MonitoringInfo.LabelsEntry")
	proto.RegisterType((*MonitoringInfoTypeUrns)(nil), "org.apache.beam.model.pipeline.v1.MonitoringInfoTypeUrns")
	proto.RegisterExtension(E_LabelProps)
	proto.RegisterExtension(E_MonitoringInfoSpec)
}

func init() { proto.RegisterFile("metrics.proto", fileDescriptor_6039342a2ba47b72) }

var fileDescriptor_6039342a2ba47b72 = []byte{
	// 1955 bytes of a gzipped FileDescriptorProto
	0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff, 0xdc, 0x98, 0xcf, 0x8f, 0x1c, 0x47,
	0x15, 0xc7, 0x5d, 0x33, 0xbb, 0xeb, 0x6c, 0x8d, 0x77, 0xdd, 0x5b, 0xbb, 0xc4, 0x43, 0x91, 0x78,
	0x6b, 0xc7, 0x81, 0x6c, 0x24, 0x98, 0xc8, 0xce, 0xc6, 0x49, 0xc6, 0x89, 0xd0, 0xec, 0x4c, 0x67,
	0x33, 0x64, 0x7e, 0xa9, 0xa7, 0x27, 0x8e, 0x7d, 0x69, 0xd5, 0x74, 0xd7, 0xee, 0xb4, 0xdc, 0xd3,
	0xdd, 0x74, 0xd7, 0xac, 0xbd, 0x3e, 0x92, 0x9b, 0x91, 0x41, 0x04, 0x08, 0x3f, 0x14, 0x88, 0xb0,
	0x10, 0x06, 0x04, 0x08, 0x89, 0x80, 0x14, 0x11, 0x24, 0x14, 0x82, 0xc4, 0x85, 0x0b, 0xe2, 0x00,
	0x12, 0x08, 0x0e, 0xf0, 0x0f, 0x20, 0xc4, 0x21, 0x27, 0x54, 0x5d, 0xf3, 0xab, 0x67, 0x66, 0x7f,
	0x21, 0x2f, 0x87, 0xdc, 0xba, 0xab, 0xea, 0xd5, 0xfb, 0x7c, 0x5f, 0x55, 0x57, 0xd7, 0x7b, 0x70,
	0xa1, 0xc3, 0x78, 0x60, 0x9b, 0x61, 0xd6, 0x0f, 0x3c, 0xee, 0xa1, 0x35, 0x2f, 0xd8, 0xc9, 0x52,
	0x9f, 0x9a, 0x6d, 0x96, 0x6d, 0x31, 0xda, 0xc9, 0x76, 0x3c, 0x8b, 0x39, 0x59, 0xdf, 0xf6, 0x99,
	0x63, 0xbb, 0x2c, 0xbb, 0x7b, 0x11, 0x7f, 0x44, 0xb4, 0x1b, 0x41, 0xd7, 0x75, 0x59, 0x60, 0x50,
	0xdf, 0x96, 0x96, 0x98, 0xec, 0x78, 0xde, 0x8e, 0xc3, 0x9e, 0x8c, 0xde, 0x5a, 0xdd, 0xed, 0x27,
	0x2d, 0x16, 0x9a, 0x81, 0xed, 0x73, 0x2f, 0xe8, 0x8d, 0x58, 0x1d, 0x1f, 0xc1, 0xed, 0x0e, 0x0b,
	0x39, 0xed, 0xf8, 0x72, 0x40, 0xe6, 0x6d, 0x00, 0x51, 0xc5, 0x73, 0x6d, 0xee, 0x05, 0xb6, 0xbb,
	0x53, 0x72, 0xb7, 0xbd, 0x86, 0xcf, 0x4c, 0xa4, 0xc0, 0x64, 0x37, 0x70, 0xd3, 0x80, 0x80, 0xf5,
	0x79, 0x4d, 0x3c, 0x22, 0x04, 0x67, 0xf8, 0x9e, 0xcf, 0xd2, 0x89, 0xa8, 0x29, 0x7a, 0x46, 0x8f,
	0xc3, 0xb3, 0x01, 0xfb, 0x6c, 0xd7, 0x0e, 0x98, 0x65, 0x38, 0xb4, 0xc5, 0x9c, 0x30, 0x9d, 0x24,
	0xc9, 0xf5, 0x79, 0x6d, 0xb1, 0xdf, 0x5c, 0x8e, 0x5a, 0x51, 0x0d, 0xa6, 0xa8, 0xeb, 0x7a, 0x9c,
	0x72, 0xdb, 0x73, 0xc3, 0xf4, 0x0c, 0x49, 0xae, 0xa7, 0x2e, 0x7d, 0x2a, 0x7b, 0xa8, 0xf0, 0x6c,
	0x7e, 0x60, 0xa5, 0x8d, 0xce, 0x90, 0xd9, 0x80, 0x70, 0xd8, 0x25, 0x68, 0x6f, 0xb0, 0xbd, 0x3e,
	0xed, 0x0d, 0xb6, 0x87, 0x56, 0xe0, 0xec, 0x2e, 0x75, 0xba, 0x7d, 0x5c, 0xf9, 0x92, 0xf9, 0xc2,
	0x2a, 0x5c, 0x9e, 0x14, 0x1b, 0x66, 0xfe, 0x73, 0x1e, 0xce, 0xa8, 0x6e, 0xb7, 0x83, 0xee, 0x03,
	0xb8, 0xd8, 0x6c, 0xa8, 0x9a, 0xd1, 0x68, 0x56, 0x8c, 0x52, 0x55, 0xbf, 0xbc, 0xa1, 0x9c, 0xc2,
	0xaf, 0x83, 0x3f, 0xdc, 0xbf, 0xff, 0xc6, 0xdc, 0xe7, 0x01, 0x7c, 0x54, 0x50, 0xe6, 0xe4, 0xea,
	0xe5, 0xba, 0x21, 0x0b, 0x72, 0x61, 0xb7, 0x63, 0xd8, 0x2e, 0xbf, 0xbc, 0x91, 0xdb, 0xbd, 0x88,
	0x3e, 0x3a, 0xd2, 0x1d, 0xc6, 0xba, 0x30, 0xac, 0xeb, 0x5a, 0xbe, 0xda, 0x78, 0xb1, 0xa6, 0x55,
	0xf0, 0x7c, 0x35, 0x5f, 0x51, 0x1b, 0xf5, 0x7c, 0x41, 0xc5, 0x33, 0xe2, 0x31, 0x73, 0x09, 0xa6,
	0xfa, 0x0b, 0x27, 0xd4, 0x5c, 0x68, 0x6a, 0x55, 0xd2, 0xe5, 0xb6, 0x63, 0xdf, 0x66, 0x16, 0xe1,
	0x1e, 0x09, 0x98, 0xef, 0x05, 0x9c, 0x08, 0x87, 0x44, 0xce, 0x9e, 0x45, 0x3f, 0x04, 0xf0, 0xec,
	0x80, 0xb4, 0x58, 0x6b, 0x6e, 0x96, 0x55, 0x05, 0xe0, 0xaf, 0x48, 0xd4, 0xbb, 0x00, 0x9e, 0x9f,
	0x8a, 0x6a, 0x79, 0xdd, 0x96, 0xc3, 0x04, 0x2b, 0x9e, 0x60, 0x1d, 0xf4, 0x3d, 0x78, 0xd8, 0xf7,
	0x00, 0x3c, 0x17, 0xc1, 0x16, 0x4b, 0x0d, 0x5d, 0x2b, 0x6d, 0x36, 0xf5, 0x52, 0xad, 0xda, 0x8b,
	0x6f, 0x02, 0x7f, 0x57, 0x42, 0x7f, 0x0b, 0xc0, 0x4f, 0x4c, 0x40, 0x5b, 0x76, 0xc8, 0x03, 0xbb,
	0xd5, 0x15, 0xbe, 0x86, 0x81, 0xce, 0xc4, 0xe0, 0xa7, 0x8e, 0x79, 0xf0, 0x22, 0x7e, 0x0b, 0x60,
	0x7a, 0x52, 0x44, 0x2f, 0xf4, 0x49, 0xfc, 0x3d, 0xa9, 0xe2, 0x2d, 0x00, 0x1f, 0x3f, 0x58, 0xc5,
	0x70, 0x0d, 0x2e, 0xec, 0x2f, 0xe3, 0x04, 0x17, 0xe3, 0xa7, 0x00, 0x2e, 0x45, 0x3a, 0xca, 0x79,
	0x5d, 0x6d, 0xe8, 0xbd, 0x65, 0x98, 0xc1, 0x5f, 0x97, 0x02, 0xbe, 0x04, 0x20, 0x99, 0x10, 0xe0,
	0x50, 0xce, 0x42, 0x3e, 0x5c, 0x80, 0x47, 0x62, 0xe4, 0x63, 0xbd, 0x0f, 0x1e, 0xf9, 0x67, 0x00,
	0xa2, 0x51, 0xe4, 0x5e, 0xd0, 0x67, 0xf1, 0x37, 0x25, 0xf3, 0x97, 0x01, 0x5c, 0xdb, 0x8f, 0x79,
	0x18, 0xee, 0x47, 0xa7, 0x41, 0x9f, 0x60, 0xa0, 0x7f, 0x0c, 0xa0, 0x12, 0x51, 0xeb, 0xb5, 0xba,
	0xd1, 0xdf, 0xee, 0x73, 0xf8, 0x0d, 0xc9, 0xfc, 0x45, 0x00, 0x57, 0x27, 0x98, 0xb9, 0xe7, 0x1b,
	0x23, 0xfb, 0xfc, 0x63, 0x31, 0xe2, 0x78, 0xe7, 0x09, 0x6e, 0x0c, 0xc9, 0xdb, 0x0b, 0xf2, 0xe9,
	0x03, 0x37, 0x86, 0x64, 0x1a, 0xc6, 0xf8, 0x91, 0x29, 0xc4, 0x27, 0x18, 0xe2, 0x5f, 0x00, 0xb8,
	0x1c, 0x21, 0x6f, 0xd6, 0x74, 0xbd, 0x56, 0x19, 0x44, 0xf9, 0x21, 0xfc, 0xa6, 0x84, 0xfe, 0x2a,
	0x80, 0x99, 0x09, 0xe8, 0x96, 0xc7, 0xb9, 0xd7, 0x19, 0x0d, 0xf4, 0xf9, 0x18, 0xf6, 0x44, 0xff,
	0x83, 0x07, 0x7f, 0x07, 0xc0, 0x95, 0x38, 0x78, 0x2f, 0xdc, 0xf3, 0xf8, 0xdb, 0x92, 0xfc, 0x6b,
	0x00, 0x5e, 0xd8, 0x9f, 0x7c, 0x18, 0xf1, 0xd5, 0xe9, 0xe8, 0x27, 0xbb, 0xaf, 0x17, 0xd4, 0xb2,
	0x5a, 0x51, 0xab, 0xba, 0x51, 0xa8, 0x35, 0xab, 0xba, 0x02, 0xf1, 0x37, 0x24, 0xf4, 0xeb, 0x00,
	0x8e, 0xee, 0x80, 0x1c, 0x73, 0x58, 0x87, 0xb9, 0xdc, 0x30, 0xbd, 0xae, 0xcb, 0x0f, 0xf9, 0x45,
	0xa6, 0xea, 0x85, 0x5a, 0xb9, 0xac, 0x16, 0xc4, 0xb1, 0x9a, 0xf9, 0x4c, 0x9c, 0xeb, 0x8a, 0xde,
	0x66, 0x84, 0x7b, 0x9c, 0x3a, 0xa4, 0x37, 0x65, 0x48, 0xbc, 0x2e, 0xf7, 0xbb, 0x5c, 0x40, 0x52,
	0x52, 0x37, 0x3d, 0xc7, 0x61, 0xa6, 0x18, 0x4b, 0x5a, 0x7b, 0xa2, 0x41, 0x0f, 0xa8, 0x1b, 0x6e,
	0x7b, 0x41, 0x27, 0x8b, 0x7e, 0x9f, 0x80, 0x4b, 0x8d, 0x7c, 0xa5, 0x5e, 0x56, 0x8b, 0xc6, 0xe6,
	0x35, 0x5d, 0x35, 0x1a, 0xa5, 0xeb, 0xaa, 0x92, 0xc2, 0x3f, 0x4f, 0x44, 0xcc, 0x3f, 0x49, 0xc4,
	0xf7, 0x75, 0x48, 0x3b, 0xbe, 0xc3, 0x2c, 0xa3, 0xb5, 0xc7, 0x99, 0x11, 0xda, 0xb7, 0xd9, 0x51,
	0xff, 0x38, 0x31, 0x01, 0x7f, 0x02, 0x71, 0x05, 0xbf, 0x03, 0x43, 0x09, 0x62, 0x66, 0x22, 0x66,
	0x26, 0xd4, 0xb5, 0x48, 0x14, 0x1b, 0xe2, 0x6d, 0x13, 0x4a, 0x7a, 0xae, 0x09, 0x09, 0x19, 0x27,
	0xeb, 0x5e, 0x40, 0xa8, 0xe3, 0x3c, 0x21, 0xba, 0x06, 0xa2, 0x6d, 0x97, 0xf0, 0x36, 0x23, 0xfe,
	0x50, 0x6f, 0x96, 0x34, 0x84, 0x95, 0xed, 0xee, 0x10, 0x3b, 0x14, 0x0b, 0x65, 0x11, 0xd2, 0x62,
	0x26, 0xed, 0x86, 0x8c, 0x98, 0xd4, 0x31, 0xbb, 0x0e, 0xe5, 0xa2, 0x53, 0x98, 0x45, 0x8e, 0xa5,
	0x3f, 0xdb, 0xdd, 0xf5, 0x9c, 0x5d, 0x16, 0x92, 0x90, 0x05, 0x36, 0x75, 0xec, 0xdb, 0xfd, 0x31,
	0x43, 0x5f, 0x37, 0xdb, 0xb6, 0xd9, 0x16, 0x93, 0x16, 0xea, 0x4d, 0x62, 0xbb, 0x9c, 0xb9, 0xa1,
	0xbd, 0xcb, 0xb2, 0xe8, 0xcf, 0x00, 0xa2, 0x86, 0x9e, 0xd7, 0x74, 0x63, 0xb3, 0x59, 0x2d, 0x96,
	0x55, 0xa3, 0xd2, 0x50, 0x0b, 0x0d, 0xe5, 0x0c, 0xfe, 0xb5, 0xdc, 0x04, 0xef, 0x00, 0x78, 0x79,
	0x34, 0xa0, 0x3e, 0x0d, 0x2c, 0xcf, 0x60, 0xb7, 0x98, 0x29, 0x03, 0x26, 0xae, 0xa0, 0xb9, 0x90,
	0xd3, 0x80, 0x1b, 0xad, 0xae, 0x6b, 0x39, 0xcc, 0xe8, 0x84, 0xcc, 0x0c, 0x8f, 0x7e, 0x83, 0xca,
	0xbc, 0x1a, 0x8f, 0x6d, 0x69, 0x64, 0x77, 0x84, 0xdc, 0xee, 0x50, 0xce, 0x2c, 0x32, 0xf0, 0x47,
	0x84, 0x3f, 0x11, 0x44, 0xa1, 0x2e, 0x72, 0x4b, 0xa4, 0xdb, 0xed, 0xae, 0x2b, 0x77, 0x8c, 0xed,
	0x12, 0x4a, 0x22, 0x4a, 0xf4, 0x37, 0x00, 0x57, 0xea, 0x5a, 0xad, 0xa0, 0x36, 0x1a, 0x71, 0x75,
	0x0b, 0xf8, 0x7d, 0xa9, 0xee, 0x57, 0x00, 0x3e, 0x7b, 0xa8, 0x3a, 0x3f, 0xf0, 0x4c, 0x16, 0x86,
	0xff, 0xab, 0xbe, 0xeb, 0x71, 0x7d, 0x2f, 0x1f, 0x5d, 0x5f, 0xcf, 0xf1, 0x01, 0x0a, 0xff, 0x0a,
	0xe0, 0xf2, 0x8b, 0xa5, 0x6a, 0xa9, 0xf1, 0x52, 0x5c, 0xe0, 0x22, 0xfe, 0x8d, 0x14, 0xf8, 0x2e,
	0x80, 0xcf, 0x1c, 0x2a, 0x70, 0xdb, 0x76, 0xed, 0xb0, 0xfd, 0xff, 0xd7, 0x27, 0xfd, 0xf6, 0xe4,
	0x91, 0x69, 0xfa, 0x7e, 0x09, 0x60, 0x4a, 0xaf, 0xe9, 0xf9, 0x72, 0x4f, 0xd7, 0x59, 0xfc, 0x03,
	0xa9, 0xeb, 0x1e, 0x80, 0x1b, 0x31, 0x5d, 0xbc, 0x7f, 0x44, 0x8c, 0x8b, 0x8b, 0x50, 0x8e, 0x2d,
	0xaa, 0x10, 0x17, 0xb5, 0x71, 0x8c, 0x45, 0x1b, 0x90, 0xa0, 0xb7, 0x12, 0x70, 0xf1, 0x6a, 0x4d,
	0x7b, 0xd9, 0xd0, 0xd4, 0x4a, 0xbe, 0x54, 0x2d, 0x55, 0xb7, 0x14, 0x05, 0xdf, 0x91, 0x07, 0xd5,
	0x6b, 0x09, 0xf8, 0xc9, 0x7d, 0x04, 0xf8, 0x81, 0xb7, 0x13, 0xb0, 0x30, 0xcc, 0x05, 0xac, 0x43,
	0x6d, 0xd7, 0x76, 0x77, 0x04, 0x78, 0x3a, 0x06, 0x3e, 0x18, 0x33, 0xc6, 0xfd, 0xa3, 0xb1, 0x93,
	0xea, 0xcd, 0xe8, 0xa4, 0x1a, 0x4c, 0x44, 0x68, 0xa7, 0x7f, 0x40, 0xdd, 0xf4, 0x82, 0x1b, 0x64,
	0xdb, 0x0b, 0x08, 0xa3, 0x66, 0x9b, 0x50, 0x93, 0xdb, 0xbb, 0xac, 0x7f, 0x5c, 0x64, 0x89, 0x3a,
	0xd9, 0x28, 0x7e, 0x1f, 0x01, 0x0b, 0xa3, 0xd3, 0x84, 0x8a, 0x55, 0xb2, 0x98, 0xcf, 0x5c, 0x4b,
	0xf4, 0x8c, 0xcd, 0xea, 0x7a, 0x9c, 0x84, 0x6d, 0x1a, 0x30, 0x8b, 0xdc, 0xb4, 0x79, 0x9b, 0x50,
	0x77, 0x8f, 0x78, 0xbc, 0xcd, 0x82, 0x71, 0x37, 0xc3, 0x08, 0x15, 0x6a, 0xe2, 0x48, 0xd7, 0xd5,
	0xa2, 0xb2, 0x74, 0x9c, 0x08, 0x99, 0x9e, 0x38, 0x63, 0x39, 0xb3, 0x3e, 0xb4, 0x11, 0xfa, 0x3e,
	0x80, 0xe7, 0x8a, 0x79, 0x3d, 0x6f, 0x14, 0x5e, 0xca, 0x57, 0xab, 0x6a, 0xd9, 0xd0, 0xd4, 0x7c,
	0xd1, 0x28, 0x55, 0x8b, 0xea, 0xab, 0x0a, 0xc2, 0x77, 0xe4, 0xd7, 0xf0, 0xda, 0x58, 0xb6, 0x65,
	0x51, 0x4e, 0x0d, 0xb3, 0x4d, 0x5d, 0x97, 0x39, 0xb9, 0x80, 0x51, 0xcb, 0x10, 0x2c, 0xb7, 0x8e,
	0xb1, 0xff, 0xc7, 0xaf, 0x12, 0x32, 0x46, 0xd4, 0x8a, 0x44, 0xdd, 0xea, 0xef, 0x75, 0xe1, 0x87,
	0xf4, 0xfc, 0x64, 0x33, 0x59, 0x98, 0x8e, 0xe7, 0xe3, 0x51, 0xbd, 0xa0, 0x1e, 0x78, 0x7e, 0x88,
	0x10, 0x9c, 0x71, 0x69, 0x87, 0xf5, 0xb2, 0xfa, 0xe8, 0x39, 0xf3, 0xaf, 0x24, 0x5c, 0x8c, 0x1b,
	0x1c, 0xb1, 0x52, 0x91, 0x86, 0xa7, 0x7d, 0xba, 0xe7, 0x78, 0xd4, 0x4a, 0x27, 0x09, 0x58, 0x3f,
	0xa3, 0xf5, 0x5f, 0x51, 0x13, 0xce, 0xf5, 0x4a, 0x17, 0xb2, 0x2a, 0xf1, 0xc2, 0x11, 0xaa, 0x12,
	0x71, 0x84, 0xac, 0x2c, 0x72, 0xa8, 0x2e, 0x0f, 0xf6, 0xb4, 0xde, 0x64, 0xf8, 0x39, 0x98, 0x1a,
	0x69, 0x3e, 0x6a, 0x85, 0x22, 0x97, 0x78, 0x16, 0x64, 0xee, 0x26, 0xe0, 0xca, 0x94, 0xa8, 0x84,
	0x68, 0x0d, 0xce, 0x0f, 0xc2, 0xad, 0x9c, 0xc2, 0xe8, 0xde, 0x1f, 0xff, 0xfe, 0xcf, 0xe4, 0x19,
	0x38, 0xb2, 0x08, 0xe8, 0x31, 0x38, 0x7a, 0x0b, 0x51, 0x00, 0x5e, 0x8e, 0x06, 0x2d, 0xc4, 0x9a,
	0xd1, 0x25, 0x88, 0xae, 0x96, 0xaa, 0xc5, 0xda, 0xd5, 0x52, 0x75, 0xcb, 0x68, 0xe8, 0x5a, 0x5e,
	0x57, 0xb7, 0xae, 0x29, 0x09, 0x8c, 0xa3, 0xc1, 0x2b, 0xd3, 0x7a, 0x51, 0x1a, 0xce, 0x16, 0x6a,
	0x45, 0x55, 0x53, 0x92, 0x78, 0x21, 0x1a, 0x76, 0xba, 0xd7, 0x20, 0x7c, 0xaa, 0xd5, 0x57, 0x4a,
	0x5a, 0xad, 0x2a, 0xae, 0x84, 0xca, 0xcc, 0xd0, 0xe7, 0x48, 0x33, 0x22, 0x70, 0x78, 0xf9, 0x54,
	0x66, 0xf1, 0x52, 0x34, 0x26, 0x35, 0xd2, 0x88, 0x1e, 0x86, 0xd1, 0x9d, 0x54, 0x99, 0xc3, 0x67,
	0xa2, 0xce, 0x39, 0xf9, 0x9e, 0x79, 0x77, 0x16, 0x3e, 0x1c, 0x8f, 0x87, 0xbe, 0xe7, 0xb3, 0x66,
	0xe0, 0x86, 0x99, 0xef, 0xcc, 0xf6, 0x0a, 0x37, 0x4f, 0xc1, 0xc5, 0x41, 0xc9, 0xc6, 0xd0, 0xaf,
	0xd5, 0x55, 0xe5, 0x14, 0x5e, 0xbd, 0xf7, 0xf6, 0x07, 0xef, 0xcd, 0xee, 0xbf, 0x7b, 0xd1, 0xd3,
	0xf0, 0xec, 0xb0, 0x7a, 0x22, 0xad, 0x00, 0x26, 0x91, 0xd5, 0x01, 0xe5, 0x11, 0xa4, 0xc2, 0x73,
	0x93, 0x75, 0x0c, 0x69, 0x9e, 0xc0, 0xeb, 0x91, 0xf9, 0x11, 0xae, 0x8b, 0x68, 0x0b, 0xa6, 0xa7,
	0x54, 0x12, 0xe4, 0x3c, 0x49, 0xfc, 0x44, 0x34, 0xcf, 0x51, 0x2a, 0x04, 0xe8, 0x39, 0xb8, 0x34,
	0x9a, 0xca, 0xcb, 0x19, 0x66, 0x70, 0x26, 0x9a, 0xe1, 0xc0, 0x4c, 0x1d, 0x5d, 0x81, 0x28, 0x96,
	0x52, 0x4b, 0xdb, 0x59, 0x7c, 0x21, 0xb2, 0x3d, 0x38, 0x61, 0x46, 0xcf, 0x40, 0x65, 0x24, 0xb3,
	0x95, 0xa6, 0x73, 0x78, 0x2d, 0x32, 0x3d, 0x28, 0x73, 0x15, 0xc0, 0xa3, 0x29, 0xa6, 0xb4, 0x3c,
	0x3d, 0x15, 0x78, 0x3c, 0xbf, 0x7c, 0x01, 0x2e, 0xc7, 0x53, 0x3d, 0x69, 0xfc, 0x10, 0x7e, 0x2c,
	0x32, 0x3e, 0x24, 0x8f, 0x43, 0x9f, 0x86, 0x2b, 0x63, 0x09, 0x97, 0xb4, 0x9f, 0xc7, 0x1f, 0x8f,
	0xec, 0x0f, 0x4b, 0xa6, 0xd0, 0x45, 0xb8, 0x50, 0xd7, 0x6a, 0x5b, 0x9a, 0xb8, 0x1f, 0x46, 0x96,
	0x10, 0x9f, 0x8f, 0x2c, 0xf7, 0xfd, 0x93, 0xe4, 0x3e, 0x07, 0x60, 0x2a, 0x3a, 0x14, 0xc4, 0x3f,
	0xc8, 0x0f, 0xd1, 0x5a, 0x56, 0xd6, 0x64, 0xb3, 0xfd, 0x9a, 0x6c, 0x56, 0xec, 0xe0, 0x57, 0xc4,
	0xe7, 0x5f, 0xf3, 0x65, 0x7d, 0xf4, 0xfd, 0x3b, 0x7f, 0x79, 0x9e, 0x80, 0xf5, 0xd4, 0xa5, 0x2b,
	0xc7, 0x3e, 0x8c, 0x86, 0x07, 0xa8, 0x06, 0x9d, 0xc1, 0x73, 0xee, 0x2e, 0x80, 0x2b, 0x9d, 0xc1,
	0x40, 0xc3, 0x76, 0xb7, 0x3d, 0x23, 0xf4, 0x99, 0x79, 0x14, 0x9a, 0x0f, 0xfe, 0xfd, 0x8f, 0x56,
	0x44, 0xf3, 0xf4, 0xb1, 0x69, 0x1a, 0x3e, 0x33, 0x35, 0xd4, 0x99, 0x68, 0xdb, 0x7c, 0x1e, 0x1e,
	0x5e, 0xf5, 0xde, 0x84, 0x15, 0x19, 0xce, 0xbc, 0x6f, 0x5f, 0x4f, 0xf5, 0x3b, 0x8c, 0xdd, 0x8b,
	0xad, 0xb9, 0x08, 0xf6, 0xa9, 0xff, 0x06, 0x00, 0x00, 0xff, 0xff, 0x1f, 0x47, 0x01, 0x2c, 0x49,
	0x17, 0x00, 0x00,
}
